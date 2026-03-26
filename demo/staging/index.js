export default {
    async fetch(request, env, context) {
        const url = new URL(request.url);

        // 1. Assets directos y archivos con extensión (evita procesar CSS/JS)
        if (url.pathname.startsWith('/v000') || (url.pathname !== '/' && url.pathname.includes('.'))) {
            return env.ASSETS.fetch(request);
        }

        // 2. Variables de entorno y headers
        const country = request.headers.get('cf-ipcountry') || 'XX';
        const cookieHeader = request.headers.get('Cookie') || '';

        // 3. LA CLAVE: Prioridad a la Variable de Entorno
        // Solo usamos la cookie si NO hay una versión por defecto definida (fallback)
        // O si quieres permitir que una versión de "QA" sobrescriba la oficial (opcional)
        const defaultVersion = env.DEFAULT_VERSION || 'v0000000001';

        // Si quieres que el usuario PUEDA forzar una versión distinta (ej. para pruebas),
        // deja la lógica de la cookie. Si quieres que sea IMPOSIBLE quedarse atrás,
        // usa directamente defaultVersion.
        const version = defaultVersion;

        // 4. Determinar el archivo
        let fileName = 'index.html';
        if (country === 'ES') fileName = 'index_ES.html';
        if (country === 'CA') fileName = 'index_CA.html';

        let targetFile = url.pathname === '/' ? fileName : url.pathname.substring(1);
        if (!targetFile.endsWith('.html')) targetFile += '.html';

        const targetPath = `/${version}/${targetFile}`;

        // 5. Fetch al asset
        const assetUrl = new URL(targetPath, url.origin);
        const assetResponse = await env.ASSETS.fetch(new Request(assetUrl, { redirect: "manual" }));

        let finalResponse = assetResponse;
        if (assetResponse.status >= 300 && assetResponse.status < 400) {
            const location = assetResponse.headers.get("location");
            if (location) finalResponse = await env.ASSETS.fetch(new URL(location, url.origin));
        }

        if (!finalResponse.ok) {
            return new Response(`Error: ${targetPath} no existe.`, { status: 404 });
        }

        const response = new Response(finalResponse.body, finalResponse);

        // 6. Actualizamos la Cookie para que coincida con la versión actual
        // Así, si en el futuro necesitas leerla, siempre estará sincronizada.
        response.headers.set('Set-Cookie', `user-country=${country}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);
        response.headers.append('Set-Cookie', `app-version=${version}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);

        response.headers.set('x-worker-active', 'true');
        response.headers.set('x-resolved-version', version);

        return response;
    }
};