export default {
    async fetch(request, env, context) {
        const url = new URL(request.url);

        // 1. Servir directamente si es un asset versionado o estático (CSS, JS, etc.)
        if (url.pathname.startsWith('/v000') || (url.pathname !== '/' && url.pathname.includes('.'))) {
            return env.ASSETS.fetch(request);
        }

        // 2. Lógica de detección
        const country = request.headers.get('cf-ipcountry') || 'XX';
        const cookieHeader = request.headers.get('Cookie') || '';
        const versionMatch = cookieHeader.match(/app-version=(v\d+)/);
        const version = versionMatch ? versionMatch[1] : 'v0000000001';

        // 3. Determinar el archivo con extensión explícita .html
        let fileName = 'index.html';
        if (country === 'ES') fileName = 'index_ES.html';
        if (country === 'CA') fileName = 'index_CA.html';

        // Si es la raíz, usamos fileName. Si es otra ruta, nos aseguramos de que termine en .html
        let targetFile = url.pathname === '/' ? fileName : url.pathname.substring(1);
        if (!targetFile.endsWith('.html')) {
            targetFile += '.html';
        }

        const targetPath = `/${version}/${targetFile}`;

        // 4. Fetch al asset con manejo de redirección manual para evitar el 307
        const assetUrl = new URL(targetPath, url.origin);
        const assetResponse = await env.ASSETS.fetch(new Request(assetUrl, {
            redirect: "manual"
        }));

        // Si es una redirección (301/302/307/308), la seguimos nosotros internamente
        let finalResponse = assetResponse;
        if (assetResponse.status >= 300 && assetResponse.status < 400) {
            const location = assetResponse.headers.get("location");
            if (location) {
                finalResponse = await env.ASSETS.fetch(new URL(location, url.origin));
            }
        }

        if (!finalResponse.ok) {
            return new Response(`Error cargando asset: ${targetPath} (Status: ${finalResponse.status})`, { status: finalResponse.status });
        }

        // 5. Construir respuesta con Cookies
        const response = new Response(finalResponse.body, finalResponse);

        response.headers.set('Set-Cookie', `user-country=${country}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);
        if (!versionMatch) {
            response.headers.append('Set-Cookie', `app-version=${version}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);
        }

        response.headers.set('x-worker-active', 'true');
        response.headers.set('x-resolved-path', targetPath);

        return response;
    }
};