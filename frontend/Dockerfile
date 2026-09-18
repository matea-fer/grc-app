# --- build: prevedi Angular u statiku ---
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# --- runtime: nginx servira statiku i proxyja /api na backend ---
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
# Angular application builder izlaz: dist/<projekt>/browser
COPY --from=build /app/dist/demo-frontend/browser /usr/share/nginx/html
EXPOSE 80
