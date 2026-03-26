export default {
    async fetch(request, env, context) {
        const url = new URL(request.url);

        // 1. Assets directos y exclusiones
        const isStaticAsset = /\.(jpg|jpeg|png|gif|css|js|ico|svg|woff2)$/i.test(url.pathname);
        if (url.pathname.startsWith('/v000') || isStaticAsset) {
            return env.ASSETS.fetch(request);
        }

        // 2. Parámetros y Cabeceras
        const country = request.headers.get('cf-ipcountry') || 'XX';
        const cookieHeader = request.headers.get('Cookie') || '';

        // --- LÓGICA DE SELECCIÓN DE VERSIÓN ---
        // Prioridad 1: Parámetro en URL (?force_version=v0000000003)
        const forceVersion = url.searchParams.get('force_version');

        // Prioridad 2: Cookie existente
        const versionMatch = cookieHeader.match(/app-version=(v\d+)/);

        // Prioridad 3: Variable de entorno (Default)
        const defaultVersion = env.DEFAULT_VERSION || 'v0000000001';

        // Resolución final
        const version = forceVersion || (versionMatch ? versionMatch[1] : defaultVersion);

        // 3. Determinar archivo por País
        let fileName = 'index.html';
        if (country === 'ES') fileName = 'index_ES.html';
        if (country === 'CA') fileName = 'index_CA.html';

        let targetPath = url.pathname === '/'
            ? `/${version}/${fileName}`
            : `/${version}${url.pathname.endsWith('.html') ? url.pathname : url.pathname + '.html'}`;

        // 4. Fetch al asset
        let assetResponse = await env.ASSETS.fetch(new Request(new URL(targetPath, url.origin), request));

        // Fallback si el archivo de país no existe en esa versión específica
        if (assetResponse.status === 404 && (country === 'ES' || country === 'CA')) {
            assetResponse = await env.ASSETS.fetch(new Request(new URL(`/${version}/index.html`, url.origin), request));
        }

        if (!assetResponse.ok) {
            return new Response(`Error: La versión ${version} o el archivo ${targetPath} no existen.`, { status: 404 });
        }

        // 5. Construir Respuesta con Cookies
        const response = new Response(assetResponse.body, assetResponse);

        // Seteamos cookies para "recordar" la elección
        response.headers.set('Set-Cookie', `user-country=${country}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);
        response.headers.append('Set-Cookie', `app-version=${version}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);

        // Headers de depuración
        response.headers.set('x-resolved-version', version);
        response.headers.set('x-country-detected', country);
        if (forceVersion) response.headers.set('x-version-forced', 'true');

        return response;
    }
};