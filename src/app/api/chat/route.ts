import { GoogleGenerativeAI } from '@google/generative-ai';
import { GoogleGenerativeAIStream, StreamingTextResponse } from 'ai';
import { fetchPlayerStats, OSRS_SKILLS, PlayerStat } from '@/lib/osrs';
import { prisma } from '@/lib/prisma';

export const maxDuration = 30; // max duration 30 seconds

// Initialize Google Generative AI
const genAI = new GoogleGenerativeAI(process.env.GOOGLE_GENERATIVE_AI_API_KEY || '');

export async function POST(req: Request) {
    try {
        const { messages, username, accountType, sessionId } = await req.json();

        let stats: PlayerStat[] | null = null;
        if (username) {
            stats = await fetchPlayerStats(username, accountType);
        }

        // Build the system prompt
        let systemPrompt = `You are an expert Old School RuneScape (OSRS) assistant. You provide incredibly accurate, meta-relevant advice. Keep answers concise. Use modern Runescape terminology.\n\n`;

        if (username) {
            systemPrompt += `The player's username is "${username}". They are playing on a ${accountType.toUpperCase()} account.\n`;

            if (accountType === 'ironman' || accountType === 'hardcore' || accountType === 'ultimate') {
                systemPrompt += `IM/HCIM/UIM RESTRICTIONS APPLY: The player CANNOT use the Grand Exchange, cannot trade other players, and cannot pick up drops from others. They must gather all items themselves. Tailor your advice strictly to an Ironman playstyle.\n`;
                if (accountType === 'ultimate') {
                    systemPrompt += `ULTIMATE IRONMAN RESTRICTIONS APPLY: The player cannot use banks!\n`;
                }
            }

            if (stats) {
                systemPrompt += `\nHere are their current skill levels:\n`;
                stats.forEach(s => {
                    if (s.skill !== 'Overall') {
                        systemPrompt += `- ${s.skill}: ${s.level}\n`;
                    }
                });
                systemPrompt += `\nTake these exact levels into account when giving advice. Don't recommend content they do not have the levels for unless explaining it as a future goal.`;
            } else {
                systemPrompt += `\n(Could not fetch their stats from the Hiscores, they might not be ranked yet).`;
            }
        } else {
            systemPrompt += `The player has not provided their username, so give general OSRS advice.`;
        }

        // Convert messages to Gemini format, injecting the system prompt
        const geminiMessages = messages.map((m: any) => ({
            role: m.role === 'user' ? 'user' : 'model',
            parts: [{ text: m.content }]
        }));

        // Inject system instructions as the very first history element
        geminiMessages.unshift({ role: 'model', parts: [{ text: 'Understood. I will act as the exact OSRS assistant you described.' }] });
        geminiMessages.unshift({ role: 'user', parts: [{ text: `SYSTEM INSTRUCTIONS:\n${systemPrompt}` }] });

        const model = genAI.getGenerativeModel({ model: 'gemini-2.5-flash' });

        // Call Gemini directly
        const geminiStream = await model.generateContentStream({
            contents: geminiMessages
        });

        const stream = GoogleGenerativeAIStream(geminiStream, {
            async onCompletion(completion) {
                // Save the interaction to the database
                try {
                    let currentSessionId = sessionId;

                    if (!currentSessionId && username) {
                        const newSession = await prisma.session.create({
                            data: {
                                username: username,
                                accountType: accountType || 'normal',
                                title: messages[0]?.content.substring(0, 30) + '...'
                            }
                        });
                        currentSessionId = newSession.id;
                    }

                    if (currentSessionId) {
                        const lastUserMessage = messages[messages.length - 1];

                        if (lastUserMessage.role === 'user') {
                            await prisma.message.create({
                                data: {
                                    sessionId: currentSessionId,
                                    role: 'user',
                                    content: lastUserMessage.content
                                }
                            });
                        }

                        await prisma.message.create({
                            data: {
                                sessionId: currentSessionId,
                                role: 'assistant',
                                content: completion
                            }
                        });
                    }
                } catch (dbError) {
                    console.error("Failed to save chat to DB:", dbError);
                }
            }
        });

        return new StreamingTextResponse(stream);
    } catch (error) {
        console.error("Chat API Error:", error);
        return new Response(JSON.stringify({ error: 'Failed to process chat' }), { status: 500 });
    }
}
