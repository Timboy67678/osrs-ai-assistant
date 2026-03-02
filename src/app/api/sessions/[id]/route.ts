import { prisma } from '@/lib/prisma';
import { NextResponse } from 'next/server';

export async function DELETE(
    req: Request,
    { params }: { params: Promise<{ id: string }> }
) {
    try {
        const resolvedParams = await params;
        const id = resolvedParams.id;

        if (!id) {
            return NextResponse.json({ error: 'Session ID is required' }, { status: 400 });
        }

        await prisma.session.delete({
            where: {
                id: id,
            },
        });

        return NextResponse.json({ message: 'Session deleted successfully' }, { status: 200 });
    } catch (error) {
        console.error('Error deleting session:', error);
        return NextResponse.json({ error: 'Failed to delete session' }, { status: 500 });
    }
}
