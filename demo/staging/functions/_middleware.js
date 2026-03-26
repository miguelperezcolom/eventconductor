export async function onRequest(context) {
    const { request, next, env } = context;
    const url = new URL(request.url);

    // 1. Detectar país
    const country = request.headers.get('cf-ipcountry') || 'XX';

    // 2. Determinar versión (por cookie o defecto)
    const cookieHeader = request.headers.get('Cookie') || '';
    const versionMatch = cookieHeader.match(/app-version=(v\d+)/);
    const version = versionMatch ? versionMatch[1] : 'v0000000001';

    // Solo intervenimos en la raíz o archivos HTML para evitar sobrecarga en imágenes/CSS
    if (url.pathname === '/' || url.pathname.endsWith('.html')) {
        let fileName = 'index.html';
        if (country === 'ES') fileName = 'index_ES.html';
        if (country === 'CA') fileName = 'index_CA.html';

        const newPath = `/${version}/${url.pathname === '/' ? fileName : url.pathname}`;

        // IMPORTANTE: Usamos env.ASSETS.fetch para obtener el contenido
        const assetResponse = await env.ASSETS.fetch(new URL(newPath, url.origin));

        // Creamos una NUEVA respuesta basada en la anterior para asegurar que las cabeceras se limpian/añaden
        const response = new Response(assetResponse.body, assetResponse);

        // Forzamos que no se cachee la cabecera Set-Cookie de forma errónea
        response.headers.set('Set-Cookie', `user-country=${country}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);

        if (!versionMatch) {
            response.headers.append('Set-Cookie', `app-version=${version}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);
        }

        // Opcional: Para depurar, añade una cabecera que confirme que el worker funcionó
        response.headers.set('x-debug-worker', 'active');

        return response;
    }

    // Para el resto de archivos, dejamos pasar
    return next();
}