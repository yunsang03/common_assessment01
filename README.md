# Common Assessment 01

Django photo blog server and Android PhotoViewer client.
Repository: https://github.com/yunsang03/common_assessment01

## Contents

- `PhotoBlogServer`: server, migrations, API tests, socket capture, media, and coverage report.
- `PhotoViewer`: complete Android Studio project, including the Gradle wrapper.
- The Word report at the repository root contains requirement evidence.

## Run a fresh checkout

Use Python 3.12 or newer and the versions pinned in requirements.txt.
The following database setup is for a NEW checkout with no existing database.
Do not load the fixture over your working database.

```bash
cd PhotoBlogServer
python3 -m venv venv
source venv/bin/activate
python -m pip install -r requirements.txt
python manage.py migrate
python manage.py loaddata demo_data
python manage.py drf_create_token assessment_demo
python manage.py runserver 0.0.0.0:8000
```

The fixture supplies a local-only demo author (ID 1, no usable password) and
six sample posts. It contains no existing account password, session, or token.
Create an admin separately with `python manage.py createsuperuser` if needed.

Open `PhotoViewer` in Android Studio with SDK 37 installed and let Gradle sync.
In MainActivity.java, replace `PUT_YOUR_TOKEN_HERE` with the token generated
for YOUR server. Keep that token out of public commits. The default emulator
server is `http://10.0.2.2:8000`; the browser address is `http://127.0.0.1:8000`.
For cloud testing, use your own server URL and that server's token.

The server's default secret key is explicitly for local assessment use.
For deployment, set DJANGO_SECRET_KEY in the server environment.
The original working database is not published; the fixture and media make
the demonstration reproducible without exposing the original login data.

## API tests and coverage

```bash
cd PhotoBlogServer
source venv/bin/activate
python run_api_tests.py
```

Open `PhotoBlogServer/htmlcov/index.html` in a browser. The committed coverage
HTML is the captured result; running the engine regenerates it locally.
The captured socket request has its Authorization token redacted for publication.

## Five additional client features

1. OCR search over photo text, including a photo-text-only filter.
2. Contact masking with OCR candidates and manually selected rectangles.
3. Receive an image shared by another app and prefill the post composer.
4. Target-size photo optimization with preview and explicit confirmation.
5. Photo-coordinate private memos with zoom/pan alignment and local persistence.

For the share demo, send a JPEG or PNG from the emulator Files app to PhotoViewer.
For OCR and masking, a clear business-card image is suitable. For optimization,
use an image larger than the chosen target size. Memos and OCR cache are stored
on the device; memos are not uploaded to the server or shared with other users.
