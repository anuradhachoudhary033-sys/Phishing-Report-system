import spaces  # Line 1: required by Hugging Face ZeroGPU
import torch
import os
import time

# Disable Gradio SSR
os.environ["GRADIO_SSR_MODE"] = "false"

import gradio as gr
from main import app as fastapi_app


@spaces.GPU
def run_gpu_check(prompt: str = "health_check") -> dict:
    return {
        "status": "healthy",
        "engine": "SOAR Phishing Orchestrator v1.0",
        "cuda_available": torch.cuda.is_available(),
        "device": str(torch.cuda.get_device_name(0)) if torch.cuda.is_available() else "cpu",
        "runtime": "ZeroGPU Ready",
        "query": prompt,
    }


with gr.Blocks(title="SOAR Phishing Orchestrator") as demo:
    gr.Markdown(
        "# 🛡️ SOAR Phishing Orchestrator Engine\n"
        "FastAPI + ONNX ML Phishing Triage & Gmail Pub/Sub Backend is active on ZeroGPU.\n\n"
        "- **Health Check:** `/health`\n"
        "- **Triage API:** `/api/v1/triage`\n"
        "- **WebSocket Feed:** `/ws/triage`\n"
        "- **Gmail Webhook:** `/webhook/gmail`"
    )
    with gr.Row():
        txt_input = gr.Textbox(value="system_health", label="Command / Status")
        btn = gr.Button("Verify ZeroGPU & Engine Status", variant="primary")
    out = gr.JSON(label="Engine Diagnostics")
    btn.click(fn=run_gpu_check, inputs=txt_input, outputs=out)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 7860))
    # 1. demo.launch() runs the ZeroGPU startup hook and initializes the server App
    app, local_url, share_url = demo.launch(
        server_name="0.0.0.0",
        server_port=port,
        prevent_thread_lock=True
    )
    # 2. Attach all FastAPI routes directly to the launched server App
    app.include_router(fastapi_app.router)

    # 3. Keep server alive continuously
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass
