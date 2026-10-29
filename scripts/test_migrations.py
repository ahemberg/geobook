"""Migration edit check, against throwaway git repositories."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from migrations import edited_migrations

MIGRATION = "src/main/resources/db/migration/V1__first.sql"


class EditedMigrationsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.repository = Path(temporary.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "test@example.com")
        self.git("config", "user.name", "Test")
        self.write(MIGRATION, "create table a (id int);\n")
        self.write("README.md", "geobook\n")
        self.base = self.commit("base")

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.repository), *args], check=True, text=True,
                              stdout=subprocess.PIPE).stdout.strip()

    def write(self, path, content):
        file = self.repository / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content)

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD")

    def edited(self):
        return edited_migrations(self.base, "HEAD", self.repository)

    def test_adding_a_migration_and_changing_other_files_is_allowed(self):
        self.write("src/main/resources/db/migration/V2__second.sql", "create table b (id int);\n")
        self.write("README.md", "geobook, changed\n")
        self.commit("add")
        self.assertEqual(self.edited(), [])

    def test_changing_an_existing_migration_is_named(self):
        self.write(MIGRATION, "create table a (id bigint);\n")
        self.commit("edit")
        self.assertEqual(self.edited(), [MIGRATION])

    def test_deleting_an_existing_migration_is_named(self):
        self.git("rm", "-q", MIGRATION)
        self.commit("delete")
        self.assertEqual(self.edited(), [MIGRATION])

    def test_renaming_an_existing_migration_names_the_original(self):
        self.git("mv", MIGRATION, "src/main/resources/db/migration/V2__first.sql")
        self.commit("rename")
        self.assertEqual(self.edited(), [MIGRATION])


if __name__ == "__main__":
    unittest.main()
