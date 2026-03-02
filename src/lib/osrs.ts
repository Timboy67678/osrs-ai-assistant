export const OSRS_SKILLS = [
    'Overall', 'Attack', 'Defence', 'Strength', 'Hitpoints', 'Ranged', 'Prayer',
    'Magic', 'Cooking', 'Woodcutting', 'Fletching', 'Fishing', 'Firemaking',
    'Crafting', 'Smithing', 'Mining', 'Herblore', 'Agility', 'Thieving', 'Slayer',
    'Farming', 'Runecraft', 'Hunter', 'Construction', 'Sailing'
];

export type AccountType = 'normal' | 'ironman' | 'hardcore' | 'ultimate';

export interface PlayerStat {
    skill: string;
    rank: number;
    level: number;
    xp: number;
}

export async function fetchPlayerStats(username: string, accountType: AccountType = 'normal'): Promise<PlayerStat[] | null> {
    const accountTypeMap: Record<AccountType, string> = {
        normal: '',
        ironman: '_ironman',
        hardcore: '_hardcore_ironman',
        ultimate: '_ultimate'
    };

    const suffix = accountTypeMap[accountType];
    const url = `https://secure.runescape.com/m=hiscore_oldschool${suffix}/index_lite.ws?player=${encodeURIComponent(username)}`;

    try {
        const res = await fetch(url, { next: { revalidate: 3600 } }); // Cache for 1 hour

        if (!res.ok) {
            if (res.status === 404) return null; // Player not found or not on this hiscore
            throw new Error(`Failed to fetch stats: ${res.statusText}`);
        }

        const text = await res.text();
        const lines = text.split('\n');

        // We only care about the first 24 lines (Overall + 23 skills)
        const stats: PlayerStat[] = [];

        for (let i = 0; i < OSRS_SKILLS.length && i < lines.length; i++) {
            if (!lines[i].trim()) continue;

            const [rankStr, levelStr, xpStr] = lines[i].split(',');
            stats.push({
                skill: OSRS_SKILLS[i],
                rank: parseInt(rankStr, 10),
                level: parseInt(levelStr, 10),
                xp: parseInt(xpStr, 10)
            });
        }

        return stats;
    } catch (error) {
        console.error(`Error fetching player stats for ${username}:`, error);
        return null;
    }
}
