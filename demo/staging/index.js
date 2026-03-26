export default {
    async fetch(request, env, context) {
        const url = new URL(request.url);

        // 1. PREVENIR BUCLE: Si pide un asset directo, lo servimos
        if (url.pathname.startsWith('/v000') || (url.pathname !== '/' && url.pathname.includes('.'))) {
            return env.ASSETS.fetch(request);
        }

        // 2. Detectar país y versión
        const country = request.headers.get('cf-ipcountry') || 'XX';
        const cookieHeader = request.headers.get('Cookie') || '';
        const versionMatch = cookieHeader.match(/app-version=(v\d+)/);
        const version = versionMatch ? versionMatch[1] : 'v0000000001';

        // 3. Determinar archivo
        let fileName = 'index.html';
        if (country === 'ES') fileName = 'index_ES.html';
        if (country === 'CA') fileName = 'index_CA.html';

        const targetPath = `/${version}/${url.pathname === '/' ? fileName : url.pathname.substring(1)}`;
        const assetUrl = new URL(targetPath, url.origin);

        // 4. Fetch al asset
        const assetResponse = await env.ASSETS.fetch(new Request(assetUrl, request));

        if (!assetResponse.ok) {
            return assetResponse;
        }

        // 5. Respuesta con Cookies
        const response = new Response(assetResponse.body, assetResponse);
        response.headers.set('Set-Cookie', `user-country=${country}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);

        if (!versionMatch) {
            response.headers.append('Set-Cookie', `app-version=${version}; Max-Age=2592000; Path=/; Secure; SameSite=Lax`);
        }

        response.headers.set('x-worker-active', 'true');
        return response;
    }
};