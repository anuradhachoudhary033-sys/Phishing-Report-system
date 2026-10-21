import asyncio, json, logging, sys
logging.basicConfig(level=logging.INFO, format='%(levelname)s %(name)s: %(message)s')
sys.path.insert(0, '.')
from gmail.user_store import get_user, update_creds
from google.oauth2.credentials import Credentials
from google.auth.transport.requests import Request as GoogleAuthRequest
from googleapiclient.discovery import build
from config import GMAIL_SCOPES
EMAIL = 'anuradhachoudhary033@gmail.com'
async def debug():
    print('\n=== Step 1: get_user ===')
    user = await get_user(EMAIL)
    if not user:
        print('ERROR: NO USER IN DB'); return
    hist = user['last_history_id']
    print(f'OK last_history_id={hist}')
    print('\n=== Step 2: credentials ===')
    creds = Credentials.from_authorized_user_info(json.loads(user['creds_json']), GMAIL_SCOPES)
    print(f'valid={creds.valid} expired={creds.expired} has_refresh={bool(creds.refresh_token)}')
    if creds.expired and creds.refresh_token:
        creds.refresh(GoogleAuthRequest())
        await update_creds(EMAIL, creds.to_json())
        print('token refreshed')
    print('\n=== Step 3: build service ===')
    service = build('gmail', 'v1', credentials=creds)
    print('OK')
    print('\n=== Step 4: history.list ===')
    try:
        resp = service.users().history().list(userId='me', startHistoryId=hist, historyTypes=['messageAdded'], labelId='INBOX').execute()
        changes = resp.get('history', [])
        print(f'OK {len(changes)} changes keys={list(resp.keys())}')
        for c in changes[:2]:
            print(json.dumps(c, indent=2))
    except Exception as e:
        print(f'ERROR: {e}')
asyncio.run(debug())
