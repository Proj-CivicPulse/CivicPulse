import { timingSafeEqual } from 'node:crypto';
import type { NextFunction, Request, Response } from 'express';
import { env } from '../config/env.ts';
import { HttpError } from './errorHandler.ts';

const EXPECTED = Buffer.from(env.INTERNAL_TOKEN, 'utf8');

/**
 * Guards the service-to-service routes backend-spring calls (currently just
 * POST /complaints/:id/process). Symmetric with Spring's own /internal/* gate:
 * the caller must present X-Internal-Token equal to INTERNAL_TOKEN.
 *
 * A browser cannot set a custom header cross-origin without a preflight CORS
 * refuses, and the token is never in shipped JavaScript, so this is a real
 * boundary rather than security theatre.
 *
 * env.ts already requires INTERNAL_TOKEN to be non-empty, so "unset" cannot
 * happen here — but the length check below still makes a blank token deny
 * everything rather than match a blank header.
 */
export function internalAuth(req: Request, _res: Response, next: NextFunction): void {
    const header = req.get('x-internal-token') ?? '';
    const provided = Buffer.from(header, 'utf8');

    const ok =
        EXPECTED.length > 0 &&
        provided.length === EXPECTED.length &&
        timingSafeEqual(provided, EXPECTED);

    if (!ok) {
        throw new HttpError(401, 'UNAUTHORIZED', 'Missing or invalid internal token');
    }

    next();
}
