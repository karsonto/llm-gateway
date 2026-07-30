# Admin Web

Vite + React admin console for LLM Gateway. Built assets are written to
`../src/main/resources/static/admin` and served at `/admin/`.

## Develop

```bash
# terminal 1: gateway with sqlite
# terminal 2:
cd admin-web
npm install
npm run dev
```

Dev server proxies `/admin/api` to `http://127.0.0.1:8088`.

## Build (embed into gateway jar)

```bash
cd admin-web
npm install
npm run build
```

Then `mvn package` and open `http://localhost:8088/admin/`.

Default login: `admin` / `admin123`.
