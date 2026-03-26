export default {
    async fetch(request, env, context) {
        const url = new URL(request.url);

        // 1. Assets directos
        if (url.pathname.startsWith('/v000') || (url.pathname !== '/' && url.pathname.includes('.'))) {
            return env.ASSETS.fetch(request);
        }

        // 2. Lógica de detección
        const country = request.headers.get('cf-ipcountry') || 'XX';
        const cookieHeader = request.headers.get('Cookie') || '';
        const versionMatch = cookieHeader.match(/app-version=(v\d+)/);

        // USAMOS LA VARIABLE DE ENTORNO AQUÍ:
        const version = versionMatch ? versionMatch[1] : (env.DEFAULT_VERSION || 'v0000000001');

        // 3. Determinar el archivo
        let fileName = 'index.html';
        if (country === 'ES') fileName = 'index_ES.html';
        if (country === 'CA') fileName = 'index_CA.html';

        let targetFile = url.pathname === '/' ? fileName : url.pathname.substring(1);
        if (!targetFile.endsWith('.html')) targetFile += '.html';

        const targetPath = `/${version}/${targetFile}`;

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
        response.headers.set('Set-Cookie', `user-country=${country}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);

        // Si no había cookie, usamos la versión que hayamos determinado (la de env o la de la cookie)
        if (!versionMatch) {
            response.headers.append('Set-Cookie', `app-version=${version}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);
        }

        response.headers.set('x-worker-active', 'true');
        response.headers.set('x-default-version', env.DEFAULT_VERSION || 'no-var');
        response.headers.set('x-resolved-version', version);
        response.headers.set('x-country-detected', country);

        return response;
    }
};