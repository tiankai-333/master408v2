# API 反向代理公共段（被 http.conf / https.conf 的 location include）
proxy_pass http://backend:8003;
proxy_set_header Host $host;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Request-Id $request_id;
proxy_buffering off;
proxy_cache off;
proxy_read_timeout 300s;
proxy_connect_timeout 30s;
add_header X-Accel-Buffering no;
