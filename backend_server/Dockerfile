FROM mcr.microsoft.com/playwright/python:v1.48.0-jammy

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

WORKDIR /app

# Install Python packages
COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir -r requirements.txt

# Copy backend code, ONNX weights, and SQLite DBs
COPY . .

# Ensure runtime directories exist
RUN mkdir -p quarantine reporting/generated models/weights sandbox/baselines

# Cloud platforms inject $PORT (defaults to 8000, or 7860 on HF Spaces)
CMD ["sh", "-c", "uvicorn main:app --host 0.0.0.0 --port ${PORT:-8000}"]