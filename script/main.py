from fastapi import FastAPI, Request

app = FastAPI()

@app.post("/")
async def receive_data(request: Request):
    data = await request.json()
    print("收到数据:", data)
    return {"status": "ok"}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)