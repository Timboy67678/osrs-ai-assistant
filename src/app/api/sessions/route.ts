import { prisma } from '@/lib/prisma';

export async function GET() {
    try {
        const sessions = await prisma.session.findMany({
            orderBy: { updatedAt: 'desc' },
            include: {
                messages: {
                    orderBy: { createdAt: 'asc' }
                }
            }
        });

        return new Response(JSON.stringify(sessions), { status: 200 });
    } catch (error) {
        console.error("Failed to fetch sessions:", error);
        return new Response(JSON.stringify({ error: 'Failed to retrieve sessions' }), { status: 500 });
    }
}
