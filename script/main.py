import hashlib
import secrets
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Request, Response, Depends, Cookie, status, HTTPException
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse
from pathlib import Path
import json
from contextlib import asynccontextmanager
from datetime import datetime

DATA_FILE = Path("./data.json")
data_store: dict[str, dict] = {}
active_connections: list[WebSocket] = []

USERNAME = "admin"
PASSWORD = "ecxb114514"
COOKIE_NAME = "auth_token"

security = HTTPBasic()

def make_token(username: str, password: str) -> str:
    m = hashlib.md5()
    m.update(f"{username}{password}".encode())
    return m.hexdigest()

VALID_TOKEN = make_token(USERNAME, PASSWORD)

@asynccontextmanager
async def lifespan(app: FastAPI):
    global data_store
    if DATA_FILE.exists():
        with DATA_FILE.open("r") as f:
            data_store = json.load(f)
    yield
    with DATA_FILE.open("w") as f:
        json.dump(data_store, f, indent=2)

app = FastAPI(lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

def verify_token(token: str | None) -> bool:
    return token == VALID_TOKEN

def authenticate(credentials: HTTPBasicCredentials = Depends(security)):
    correct_username = secrets.compare_digest(credentials.username, USERNAME)
    correct_password = secrets.compare_digest(credentials.password, PASSWORD)
    if not (correct_username and correct_password):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password",
            headers={"WWW-Authenticate": "Basic"},
        )
    return credentials.username, credentials.password

@app.get("/", response_class=HTMLResponse)
async def index(request: Request, response: Response, token: str | None = Cookie(default=None), credentials: HTTPBasicCredentials = Depends(security)):
    if verify_token(token):
        return get_html_response()
    username, password = authenticate(credentials)
    token = make_token(username, password)
    response = get_html_response()
    response.set_cookie(key=COOKIE_NAME, value=token, max_age=10*365*24*60*60, httponly=True)
    return response

def get_html_response() -> HTMLResponse:
    html = """
    <!DOCTYPE html>
    <html>
    <head>
        <title>Device Monitor</title>
        <script>
            let ws;
            const dataMap = {};
            window.onload = () => {
                ws = new WebSocket("ws://" + location.host + "/ws");
                ws.onmessage = (event) => {
                    const allDevices = JSON.parse(event.data);
                    for (const d of allDevices) {
                        dataMap[d.deviceId] = d;
                    }
                    updateDisplay();
                };
            };

            function formatTimestamp(ts) {
                if (!ts) return "";
                const timestamp = Number(ts);
                if (isNaN(timestamp)) return ts;
                const date = new Date(timestamp * 1000);
                return date.toLocaleString();
            }

            function updateDisplay() {
                let container = document.getElementById("container");
                container.innerHTML = "";
                for (let id in dataMap) {
                    const d = dataMap[id];
                    container.innerHTML += `<div><strong>设备: ${id}</strong><br>
                        设备时间: ${formatTimestamp(d.time)} <br>
                        纬度: ${d.lat}, 经度: ${d.lng} <br>
                        在线: ${d.isOnline} <br>
                        启动时间: ${formatTimestamp(d.bootTime)} <br>
                        最后更新时间: ${d.lastUpdate} <br>
                        </div><hr>`;
                }
            }
        </script>
    </head>
    <body>
        <h1>设备监控数据</h1>
        <div id="container"></div>
    </body>
    </html>
    """
    return HTMLResponse(content=html)

@app.websocket("/ws")
async def websocket_endpoint(ws: WebSocket):
    token = ws.cookies.get(COOKIE_NAME)
    if not verify_token(token):
        await ws.close(code=1008)
        return
    await ws.accept()
    active_connections.append(ws)

    # ✅ 初次连接立即推送数据
    try:
        await ws.send_json(list(data_store.values()))
    except Exception:
        pass

    try:
        while True:
            await ws.receive_text()
    except WebSocketDisconnect:
        active_connections.remove(ws)

@app.post("/update")
async def update(request: Request):
    payload = await request.json()
    device_data = payload.get("data", {})
    device_id = device_data.get("deviceId")
    if not device_id:
        return {"status": "error", "message": "Missing deviceId"}

    device_data['lastUpdate'] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    data_store[device_id] = device_data

    all_devices_data = list(data_store.values())
    for ws in active_connections:
        try:
            await ws.send_json(all_devices_data)
        except Exception:
            pass

    return {"status": "ok"}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
