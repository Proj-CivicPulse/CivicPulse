import type { NextFunction, Request, Response } from 'express';
import { ZodError } from 'zod';
import { logger } from '../config/logger.ts';

/**
 * Application error with an HTTP status and a stable UPPER_SNAKE_CASE code.
 * Throw this (or use a helper below) from any handler; Express 5 forwards
 * both sync throws and rejected async promises to the error handler, so no
 * per-route try/catch is needed.
 */
export class HttpError extends Error {
    readonly status: number;
    readonly code: string;

    constructor(status: number, code: string, message: string) {
        super(message);
        this.name = 'HttpError';
        this.status = status;
        this.code = code;
    }
}

export const notImplemented = (message: string): HttpError =>
    new HttpError(501, 'NOT_IMPLEMENTED', message);

export const notFound = (message = 'Resource not found'): HttpError =>
    new HttpError(404, 'NOT_FOUND', message);

/** Wire-format error body — matches docs/api-contract.md exactly. */
function sendError(res: Response, status: number, code: string, message: string): void {
    res.status(status).json({ error: { code, message } });
}

/**
 * express.json() / body-parser rejections are plain `http-errors` objects
 * (numeric `.status`, a `.type` like `entity.too.large`). Map the ones we
 * expect to clean 4xx codes; anything else falls through to a 500.
 */
function classifyBodyParserError(
    err: unknown,
): { status: number; code: string; message: string } | null {
    if (typeof err !== 'object' || err === null) return null;
    const e = err as { type?: string; status?: number; statusCode?: number };
    const status = e.status ?? e.statusCode;

    switch (e.type) {
        case 'entity.too.large':
            return { status: 413, code: 'PAYLOAD_TOO_LARGE', message: 'Request body exceeds the 100kb limit' };
        case 'entity.parse.failed':
            return { status: 400, code: 'INVALID_JSON', message: 'Request body is not valid JSON' };
        case 'charset.unsupported':
        case 'encoding.unsupported':
            return { status: 415, code: 'UNSUPPORTED_MEDIA_TYPE', message: 'Unsupported request encoding' };
        default:
            if (typeof status === 'number' && status >= 400 && status < 500) {
                return { status, code: 'BAD_REQUEST', message: 'Malformed request' };
            }
            return null;
    }
}

/** Terminal 404 for unmatched routes. Registered after all route handlers. */
export function notFoundHandler(req: Request, res: Response): void {
    sendError(res, 404, 'NOT_FOUND', `Cannot ${req.method} ${req.path}`);
}

/**
 * Centralized error handler. Must keep all four parameters so Express
 * recognizes it as error-handling middleware. Never puts stack traces or
 * internal details in the response — those are logged server-side only.
 */
export function errorHandler(err: unknown, req: Request, res: Response, _next: NextFunction): void {
    if (err instanceof ZodError) {
        // Safe to report which field failed which rule; the submitted
        // values are never echoed back.
        const summary = err.issues
            .map((issue) => `${issue.path.join('.') || 'body'} (${issue.message})`)
            .join('; ');
        logger.warn({ path: req.path, issues: err.issues }, 'request validation failed');
        sendError(res, 400, 'VALIDATION_ERROR', `Request validation failed: ${summary}`);
        return;
    }

    if (err instanceof HttpError) {
        // These are all deliberate (404, 501, …) — log the code, not a
        // stack. A genuine server-fault status (>= 500, but not the
        // expected NOT_IMPLEMENTED stubs) is the one case worth error level.
        const isServerFault = err.status >= 500 && err.code !== 'NOT_IMPLEMENTED';
        logger[isServerFault ? 'error' : 'info'](
            { path: req.path, code: err.code, status: err.status },
            err.message,
        );
        sendError(res, err.status, err.code, err.message);
        return;
    }

    const bodyParserError = classifyBodyParserError(err);
    if (bodyParserError) {
        logger.warn(
            { path: req.path, code: bodyParserError.code, status: bodyParserError.status },
            bodyParserError.message,
        );
        sendError(res, bodyParserError.status, bodyParserError.code, bodyParserError.message);
        return;
    }

    // Unknown / unexpected: log everything, reveal nothing.
    logger.error({ err, path: req.path }, 'unhandled error');
    sendError(res, 500, 'INTERNAL_ERROR', 'An unexpected error occurred');
}
