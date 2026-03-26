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

        // LA CLAVE: Construir una Request nueva basada en la original
        // pero cambiando la URL a una ruta absoluta interna.
        const assetPath = `/${version}/${targetFile}`;
        const assetRequest = new Request(new URL(assetPath, url.origin), request);

        // Pedimos el asset a Pages
        const assetResponse = await env.ASSETS.fetch(assetRequest);

        // Si falla el asset interno, dejamos que siga el flujo normal (evita el 404 estricto)
        if (!assetResponse.ok) {
            return next();
        }

        // Clonamos la respuesta para poder modificar cabeceras
        const response = new Response(assetResponse.body, assetResponse);

        // Inyectamos Cookies
        response.headers.set('Set-Cookie', `user-country=${country}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);
        if (!versionMatch) {
            response.headers.append('Set-Cookie', `app-version=${version}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);
        }

        // Headers de diagnóstico
        response.headers.set('x-debug-worker', 'active');
        response.headers.set('x-debug-target', assetPath);

        return response;
    }

    return next();
}