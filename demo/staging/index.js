export default {
    async fetch(request, env, context) {
        const url = new URL(request.url);

        // 1. Evitar bucles y servir assets directos
        if (url.pathname.startsWith('/v000')) {
            return env.ASSETS.fetch(request);
        }

        // 2. Configuración de versión y país
        const country = request.headers.get('cf-ipcountry') || 'XX';
        const cookieHeader = request.headers.get('Cookie') || '';
        const forceVersion = url.searchParams.get('force_version');
        const versionMatch = cookieHeader.match(/app-version=(v\d+)/);
        const defaultVersion = env.DEFAULT_VERSION || 'v0000000001';

        const version = forceVersion || (versionMatch ? versionMatch[1] : defaultVersion);

        // 3. Selección de archivo (Quitamos el .html para que Pages no redirija)
        let fileName = 'index';
        if (country === 'ES') fileName = 'index_ES';
        if (country === 'CA') fileName = 'index_CA';

        // 4. Construir la ruta de destino
        // Si es la raíz, usamos nuestro fileName. Si es otra ruta, quitamos la barra inicial.
        const cleanPath = url.pathname === '/' ? fileName : url.pathname.replace(/^\/|\.html$/g, '');
        const targetPath = `/${version}/${cleanPath}`;

        // 5. Fetch al Asset (Permitiendo que siga redirecciones si Pages se pone terco)
        let assetResponse = await env.ASSETS.fetch(new URL(targetPath, url.origin), {
            redirect: "follow"
        });

        // Fallback: Si el index_ES no existe, vamos al index genérico de esa versión
        if (assetResponse.status === 404 && fileName !== 'index' && url.pathname === '/') {
            assetResponse = await env.ASSETS.fetch(new URL(`/${version}/index`, url.origin), {
                redirect: "follow"
            });
        }

        // 6. Manejo de error final
        if (!assetResponse.ok) {
            return new Response(`Error: No se encontró ${targetPath} (Status: ${assetResponse.status})`, {
                status: 404,
                headers: { 'content-type': 'text/plain;charset=UTF-8' }
            });
        }

        // 7. Respuesta con cookies fijadas
        const response = new Response(assetResponse.body, assetResponse);
        response.headers.set('Set-Cookie', `user-country=${country}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);
        response.headers.append('Set-Cookie', `app-version=${version}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);

        // Debug headers
        response.headers.set('x-resolved-path', targetPath);
        response.headers.set('x-resolved-version', version);

        return response;
    }
};