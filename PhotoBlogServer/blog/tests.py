from io import BytesIO
from tempfile import TemporaryDirectory

from PIL import Image
from django.contrib.auth import get_user_model
from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import override_settings
from django.utils import timezone
from rest_framework import status
from rest_framework.authtoken.models import Token
from rest_framework.test import APITestCase

from .models import Post


class PostAPITests(APITestCase):
    url = "/api_root/Post/"

    def setUp(self):
        media = TemporaryDirectory()
        self.addCleanup(media.cleanup)
        settings_override = override_settings(MEDIA_ROOT=media.name)
        settings_override.enable()
        self.addCleanup(settings_override.disable)
        self.user = get_user_model().objects.create_user(username="api_test_user")
        self.token = Token.objects.create(user=self.user)
        self.client.credentials(HTTP_AUTHORIZATION=f"Token {self.token.key}")

    @staticmethod
    def make_image():
        with BytesIO() as buffer:
            Image.new("RGB", (4, 4), color="blue").save(buffer, format="PNG")
            return SimpleUploadedFile(
                "api_test.png", buffer.getvalue(), content_type="image/png"
            )

    def make_payload(self):
        return {
            "author": self.user.pk,
            "title": "API upload test",
            "text": "Uploaded through the API.",
            "published_date": timezone.now().isoformat(),
            "image": self.make_image(),
        }

    def assert_rejected(self, payload, field):
        response = self.client.post(self.url, payload, format="multipart")
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST, response.data)
        self.assertIn(field, response.data)
        self.assertEqual(Post.objects.count(), 0)

    def test_get_empty_list(self):
        response = self.client.get(self.url, HTTP_ACCEPT="application/json")
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.json(), [])

    def test_get_post_fields_and_image_url(self):
        post = Post.objects.create(
            author=self.user, title="Existing post", text="Existing text",
            published_date=timezone.now(), image=self.make_image(),
        )
        response = self.client.get(self.url, HTTP_ACCEPT="application/json")
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        data = response.json()
        self.assertIsInstance(data, list)
        self.assertEqual(len(data), 1)
        self.assertEqual(set(data[0]), {
            "author", "title", "text", "created_date", "published_date", "image"
        })
        self.assertEqual(data[0]["author"], self.user.pk)
        self.assertEqual(data[0]["title"], post.title)
        self.assertEqual(data[0]["text"], post.text)
        self.assertTrue(data[0]["created_date"])
        self.assertTrue(data[0]["published_date"])
        self.assertEqual(data[0]["image"], f"http://testserver{post.image.url}")

    def test_post_image_is_saved_and_returned_in_list(self):
        response = self.client.post(self.url, self.make_payload(), format="multipart")
        self.assertEqual(response.status_code, status.HTTP_201_CREATED, response.data)
        self.assertEqual(Post.objects.count(), 1)
        post = Post.objects.get()
        self.assertEqual(post.author_id, self.user.pk)
        self.assertEqual(post.title, "API upload test")
        self.assertEqual(post.text, "Uploaded through the API.")
        self.assertIsNotNone(post.published_date)
        self.assertTrue(post.image.storage.exists(post.image.name))
        with post.image.open("rb") as saved_file:
            with Image.open(saved_file) as image:
                self.assertEqual(image.size, (4, 4))
        listing = self.client.get(self.url).json()
        self.assertEqual(len(listing), 1)
        self.assertEqual(listing[0]["title"], post.title)
        self.assertEqual(listing[0]["image"], response.data["image"])

    def test_post_missing_required_fields_is_rejected(self):
        for field in ("author", "title", "text"):
            with self.subTest(field=field):
                payload = self.make_payload()
                del payload[field]
                self.assert_rejected(payload, field)

    def test_post_title_over_200_characters_is_rejected(self):
        payload = self.make_payload()
        payload["title"] = "x" * 201
        self.assert_rejected(payload, "title")

    def test_post_unknown_author_is_rejected(self):
        payload = self.make_payload()
        payload["author"] = self.user.pk + 1000
        self.assert_rejected(payload, "author")

    def test_post_invalid_image_is_rejected(self):
        payload = self.make_payload()
        payload["image"] = SimpleUploadedFile(
            "invalid.png", b"not an image", content_type="image/png"
        )
        self.assert_rejected(payload, "image")

    def test_get_invalid_token_is_rejected(self):
        self.client.credentials(HTTP_AUTHORIZATION="Token invalid-token")
        response = self.client.get(self.url)
        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)

    def test_post_invalid_token_does_not_create_post(self):
        self.client.credentials(HTTP_AUTHORIZATION="Token invalid-token")
        response = self.client.post(self.url, self.make_payload(), format="multipart")
        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)
        self.assertEqual(Post.objects.count(), 0)

    def test_get_without_token_matches_current_public_policy(self):
        self.client.credentials()
        response = self.client.get(self.url)
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.json(), [])

    def test_head_returns_no_body(self):
        response = self.client.head(self.url)
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.content, b"")

    def test_options_advertises_get_and_post(self):
        response = self.client.options(self.url)
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        allowed = {method.strip() for method in response["Allow"].split(",")}
        self.assertTrue({"GET", "POST", "HEAD", "OPTIONS"}.issubset(allowed))
