FROM node:20-alpine as build
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
# Коммит сборки — экран сравнивает его с GET /api/v1/version и предлагает обновиться (ANO-222).
ARG GIT_COMMIT=unknown
ENV VITE_GIT_COMMIT=$GIT_COMMIT
RUN npm run build

FROM nginx:stable-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
# Ошибка в конфиге роняет сборку образа (CI «Образ фронта собирается»), а не контейнер на сервере (ANO-242).
RUN nginx -t
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
