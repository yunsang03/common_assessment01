import os
from pathlib import Path

import coverage


def main():
    root = Path(__file__).resolve().parent
    os.chdir(root)
    os.environ.setdefault("DJANGO_SETTINGS_MODULE", "mysite.settings")
    cov = coverage.Coverage(
        source=["blog"], branch=True,
        omit=["*/tests.py", "*/migrations/*"],
    )
    cov.start()
    try:
        import django
        django.setup()
        from django.conf import settings
        from django.test.utils import get_runner
        runner = get_runner(settings)(verbosity=2, interactive=False)
        failures = runner.run_tests(["blog.tests"])
    finally:
        cov.stop()
        cov.save()
        cov.report(show_missing=True)
        cov.html_report(directory="htmlcov")
    print(f"HTML report: {root / 'htmlcov' / 'index.html'}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
