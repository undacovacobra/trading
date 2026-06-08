import os
from dotenv import load_dotenv

load_dotenv()

USERNAME = os.environ["TRADOVATE_USERNAME"]
PASSWORD = os.environ["TRADOVATE_PASSWORD"]
ACCOUNT = os.environ["TRADOVATE_ACCOUNT"]
WEBHOOK_SECRET = os.environ["WEBHOOK_SECRET"]
