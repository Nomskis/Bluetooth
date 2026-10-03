import fs from 'node:fs/promises';
import path from 'node:path';

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8',
};

const SECURITY_HEADERS = {
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
  'X-Frame-Options': 'DENY',
  'Permissions-Policy': 'camera=(self), microphone=(self), display-capture=()',
  'Content-Security-Policy': [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self'",
    "img-src 'self' data: blob:",
    "media-src 'self' blob:",
    "connect-src 'self' ws: wss:",
    "frame-ancestors 'none'",
    "base-uri 'none'",
    "form-action 'self'",
  ].join('; '),
};

/** Paths that should load the single-page web client. */
function isAppRoute(pathname) {
  return pathname === '/' || pathname === '/index.html' || /^\/r\/[^/]+\/?$/.test(pathname);
}

/**
 * Minimal static file handler for the web client. Returns false when the
 * request was not handled so the caller can send a 404.
 */
export function createStaticHandler(webRoot) {
  const root = path.resolve(webRoot);

  return async function serveStatic(req, res, pathname) {
    if (req.method !== 'GET' && req.method !== 'HEAD') return false;

    let relative;
    if (isAppRoute(pathname)) {
      relative = 'index.html';
    } else {
      try {
        relative = decodeURIComponent(pathname).replace(/^\/+/, '');
      } catch {
        return false;
      }
    }

    const filePath = path.resolve(root, relative);
    if (filePath !== root && !filePath.startsWith(root + path.sep)) return false;

    let body;
    try {
      const stat = await fs.stat(filePath);
      if (!stat.isFile()) return false;
      body = await fs.readFile(filePath);
    } catch {
      return false;
    }

    const ext = path.extname(filePath).toLowerCase();
    res.writeHead(200, {
      ...SECURITY_HEADERS,
      'Content-Type': MIME_TYPES[ext] ?? 'application/octet-stream',
      'Content-Length': body.length,
      // The client is tiny; always revalidate so updates show up immediately.
      'Cache-Control': 'no-cache',
    });
    res.end(req.method === 'HEAD' ? undefined : body);
    return true;
  };
}
