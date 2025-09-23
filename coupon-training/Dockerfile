FROM python:3.9-slim

WORKDIR /app

COPY web_ui/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 5002

CMD ["python", "run_web_ui.py"]
