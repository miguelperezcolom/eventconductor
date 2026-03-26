export async function onRequest(context) {
    const { request, next, env } = context;
    const url = new URL(request.url);

    // 1. PREVENIR BUCLE
    if (url.pathname.startsWith('/v000')) {
        return next();
    }

    // 2. Datos de contexto
    const country = request.headers.get('cf-ipcountry') || 'XX';
    const cookieHeader = request.headers.get('Cookie') || '';
    const versionMatch = cookieHeader.match(/app-version=(v\d+)/);
    const version = versionMatch ? versionMatch[1] : 'v0000000001';

    // 3. Solo interceptar Raíz o HTML
    if (url.pathname === '/' || url.pathname.endsWith('.html')) {
        let fileName = 'index.html';
        if (country === 'ES') fileName = 'index_ES.html';
        if (country === 'CA') fileName = 'index_CA.html';

        const targetFile = url.pathname === '/' ? fileName : url.pathname.substring(1);

        // CONSTRUCCIÓN LIMPIA:
        // Creamos una URL nueva basada en el origen pero SIN los query params del usuario
        // para que env.ASSETS no se confunda buscando archivos con "?" en el nombre.
        const assetUrl = new URL(`/${version}/${targetFile}`, url.origin);

        const assetResponse = await env.ASSETS.fetch(assetUrl);

        if (assetResponse.status === 404) {
            return next();
        }

        const response = new Response(assetResponse.body, assetResponse);

        // Cookies
        response.headers.set('Set-Cookie', `user-country=${country}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);
        if (!versionMatch) {
            response.headers.append('Set-Cookie', `app-version=${version}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);
        }

        // Debug
        response.headers.set('x-debug-worker', 'active');
        response.headers.set('x-debug-lookup', assetUrl.pathname);

        return response;
    }

    return next();
}