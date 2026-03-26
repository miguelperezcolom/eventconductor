export default {
    async fetch(request, env, context) {
        const url = new URL(request.url);

        // 1. Assets directos (si alguien pide la ruta completa /v000.../archivo.png)
        if (url.pathname.startsWith('/v000')) {
            return env.ASSETS.fetch(request);
        }

        // 2. Parámetros y Configuración
        const country = request.headers.get('cf-ipcountry') || 'XX';
        const cookieHeader = request.headers.get('Cookie') || '';

        const forceVersion = url.searchParams.get('force_version');
        const versionMatch = cookieHeader.match(/app-version=(v\d+)/);
        const defaultVersion = env.DEFAULT_VERSION || 'v0000000001';

        const version = forceVersion || (versionMatch ? versionMatch[1] : defaultVersion);

        // 3. Determinar archivo por País
        let fileName = 'index.html';
        if (country === 'ES') fileName = 'index_ES.html';
        if (country === 'CA') fileName = 'index_CA.html';

        // 4. Construcción de la URL de destino (IMPORTANTE: Sin doble barra)
        // Eliminamos la barra inicial de pathname si existe para evitar //
        const cleanPath = url.pathname === '/' ? fileName : url.pathname.replace(/^\//, '');
        const targetPath = `${version}/${cleanPath}`;

        // Creamos la URL completa para el fetch interno
        const assetUrl = new URL(targetPath, url.origin);

        // 5. Intento de Fetch
        let assetResponse = await env.ASSETS.fetch(new Request(assetUrl, request));

        // Fallback si el archivo de país no existe: intentar el index.html de esa versión
        if (assetResponse.status === 404 && fileName !== 'index.html' && url.pathname === '/') {
            const fallbackUrl = new URL(`${version}/index.html`, url.origin);
            assetResponse = await env.ASSETS.fetch(new Request(fallbackUrl, request));
        }

        // Si sigue fallando, error descriptivo
        if (!assetResponse.ok) {
            return new Response(`Error: No se encontró ${targetPath} (Status: ${assetResponse.status})`, {
                status: 404,
                headers: { 'content-type': 'text/plain;charset=UTF-8' }
            });
        }

        // 6. Respuesta final con Cookies
        const response = new Response(assetResponse.body, assetResponse);

        response.headers.set('Set-Cookie', `user-country=${country}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);
        response.headers.append('Set-Cookie', `app-version=${version}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);

        response.headers.set('x-resolved-path', targetPath); // Para debug
        response.headers.set('x-resolved-version', version);

        return response;
    }
};