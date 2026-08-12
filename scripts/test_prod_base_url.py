import unittest

from scripts.prod_base_url import prod_base_url


class ProdBaseUrlTest(unittest.TestCase):
    def test_reads_literal_root_url(self):
        self.assertEqual(
            "https://roadtrip.floo.ca",
            prod_base_url(
                """
                roadtrip:
                  web:
                    root-url: "https://roadtrip.floo.ca/"
                """,
            ),
        )

    def test_reads_ktor_placeholder_default(self):
        self.assertEqual(
            "https://roadtrip.floo.ca",
            prod_base_url(
                """
                roadtrip:
                  web:
                    root-url: "${ROADTRIP_WEB_ROOT_URL:https://roadtrip.floo.ca/}"
                """,
            ),
        )


if __name__ == "__main__":
    unittest.main()
