export async function onRequest(context) {
    const { request, next, env } = context;
    const url = new URL(request.url);

    // 1. OBTENER PAÍS Y COOKIE DE VERSIÓN
    const country = request.headers.get('cf-ipcountry') || 'US';
    const cookieHeader = request.headers.get('Cookie') || '';

    // Extraer versión de la cookie (buscamos algo como "app-version=v0000000001")
    const versionMatch = cookieHeader.match(/app-version=(v\d+)/);
    const version = versionMatch ? versionMatch[1] : 'v0000000001'; // v1 por defecto

    // 2. LÓGICA DE ENRUTAMIENTO (Solo para la raíz o archivos HTML)
    // Si el usuario pide la raíz "/" o un archivo que no sea un asset (png, css, etc)
    if (url.pathname === '/' || url.pathname.endsWith('.html')) {

        let fileName = 'index.html';

        // Mapeo simple de país a archivo específico
        if (country === 'ES') {
            fileName = 'index_ES.html';
        } else if (country === 'CA') {
            fileName = 'index_CA.html';
        }

        // Construimos la ruta: /v000000000X/index_XX.html
        const newPath = `/${version}/${fileName}`;

        // Hacemos el fetch interno
        const assetRequest = new Request(new URL(newPath, url.origin), request);
        let response = await env.ASSETS.fetch(assetRequest);

        // Si por alguna razón el archivo específico no existe (404), intentamos el index genérico de esa versión
        if (response.status === 404) {
            response = await env.ASSETS.fetch(new Request(new URL(`/${version}/index.html`, url.origin), request));
        }

        // 3. INYECTAR COOKIES EN LA RESPUESTA
        const newResponse = new Response(response.body, response);

        // Guardamos el país detectado
        newResponse.headers.append('Set-Cookie', `user-country=${country}; Max-Age=2592000; Path=/; Secure`);

        // Si no tenía versión, se la fijamos para que siempre vea la misma (Sticky Versioning)
        if (!versionMatch) {
            newResponse.headers.append('Set-Cookie', `app-version=${version}; Max-Age=2592000; Path=/; Secure`);
        }

        return newResponse;
    }

    // Para el resto de archivos (JS, CSS, Imágenes), seguimos el flujo normal
    return next();
}