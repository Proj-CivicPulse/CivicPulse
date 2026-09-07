import type { UserRole } from '@/stores/auth.store';

/**
 * Where a signed-in account belongs.
 *
 * This replaced the /portal redirect page. Portal existed to answer one
 * question — "which home does this role get?" — and answering it with a real
 * route meant every sign-in went through an extra render and an extra history
 * entry to reach the same place. A function answers it without the round trip.
 *
 * It stays in one place for the same reason Portal did: Login, Register, and
 * both route guards need the same answer, and three copies of it would drift
 * the moment a third role appears.
 */
export function homePathForRole(role: UserRole | undefined): string {
    return role === 'officer' ? '/dashboard' : '/complaints';
}
