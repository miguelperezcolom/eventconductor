export async function onRequest(context) {
    const { request, next, env } = context;
    const url = new URL(request.url);

    // 1. PREVENIR BUCLE: Si la URL ya es para una versión específica,
    // dejamos que Cloudflare sirva el archivo estático directamente.
    if (url.pathname.startsWith('/v000')) {
        return next();
    }

    // 2. Detectar país y versión
    const country = request.headers.get('cf-ipcountry') || 'XX';
    const cookieHeader = request.headers.get('Cookie') || '';
    const versionMatch = cookieHeader.match(/app-version=(v\d+)/);
    const version = versionMatch ? versionMatch[1] : 'v0000000001';

    // 3. Lógica de enrutamiento para la raíz o archivos HTML
    if (url.pathname === '/' || url.pathname.endsWith('.html')) {
        let fileName = 'index.html';
        if (country === 'ES') fileName = 'index_ES.html';
        if (country === 'CA') fileName = 'index_CA.html';

        // Construimos la ruta interna (ej: /v0000000001/index_ES.html)
        const newPath = `/${version}/${url.pathname === '/' ? fileName : url.pathname}`;

        // Buscamos el asset en la carpeta definida en wrangler.jsonc (./public)
        const assetResponse = await env.ASSETS.fetch(new URL(newPath, url.origin));

        // Si el archivo no existe en esa subcarpeta, lanzamos el siguiente middleware o 404
        if (assetResponse.status === 404) return next();

        // Creamos la respuesta final clonando el asset y añadiendo cookies
        const response = new Response(assetResponse.body, assetResponse);

        response.headers.set('Set-Cookie', `user-country=${country}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);

        if (!versionMatch) {
            response.headers.append('Set-Cookie', `app-version=${version}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);
        }

        response.headers.set('x-debug-worker', 'active');
        return response;
    }

    return next();
}