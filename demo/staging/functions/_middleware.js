export async function onRequest(context) {
    const { request, next, env } = context;
    const url = new URL(request.url);

    // 1. PREVENIR BUCLE: Si la URL ya es para una versión, no procesamos.
    if (url.pathname.startsWith('/v000')) {
        return next();
    }

    // 2. Detectar país y versión
    const country = request.headers.get('cf-ipcountry') || 'XX';
    const cookieHeader = request.headers.get('Cookie') || '';
    const versionMatch = cookieHeader.match(/app-version=(v\d+)/);
    const version = versionMatch ? versionMatch[1] : 'v0000000001';

    // 3. Lógica de enrutamiento (Raíz o archivos .html)
    if (url.pathname === '/' || url.pathname.endsWith('.html')) {
        let fileName = 'index.html';
        if (country === 'ES') fileName = 'index_ES.html';
        if (country === 'CA') fileName = 'index_CA.html';

        // Construimos el path limpio
        // Si es la raíz '/', usamos el fileName. Si es un .html, usamos su nombre.
        const targetFile = url.pathname === '/' ? fileName : url.pathname.substring(1);
        const newPath = `/${version}/${targetFile}`;

        // IMPORTANTE: Construimos la URL de destino usando la URL original como base
        // Esto es lo más fiable para env.ASSETS.fetch
        const destinationURL = new URL(newPath, url.href);

        // Intentamos recuperar el asset
        const assetResponse = await env.ASSETS.fetch(destinationURL);

        // Si el asset no existe (404), dejamos que Pages maneje el error normalmente
        if (assetResponse.status === 404) {
            return next();
        }

        // Creamos la respuesta final clonando el cuerpo y las cabeceras del asset
        const response = new Response(assetResponse.body, assetResponse);

        // Seteamos las cookies
        response.headers.set('Set-Cookie', `user-country=${country}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);

        // Si no había cookie de versión, la ponemos ahora
        if (!versionMatch) {
            response.headers.append('Set-Cookie', `app-version=${version}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);
        }

        // Cabecera de depuración para confirmar que el middleware funcionó
        response.headers.set('x-debug-worker', 'active');
        response.headers.set('x-debug-path', newPath);

        return response;
    }

    // Para cualquier otro archivo (JS, CSS, imágenes), continuar normal
    return next();
}