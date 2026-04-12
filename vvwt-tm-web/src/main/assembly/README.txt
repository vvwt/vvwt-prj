Tournament Manager V1
=====================

Quick start
-----------

1. Extract this archive:

   tar -xzf tournament-manager-<version>.tar.gz     (Linux / macOS)
   unzip  tournament-manager-<version>.zip           (Windows)

2. Run the application:

   ./bin/tournament-manager                (Linux / macOS)
   bin\tournament-manager.bat              (Windows)

   No Java installation required — a custom JRE is bundled in runtime/.

Note (zip archives on Unix): zip archives do not carry file permissions.
After extracting a .zip on Linux or macOS you may need to make the
launcher executable:

   chmod +x bin/tournament-manager


Options
-------

Override the HTTP port (default 8080):

   ./bin/tournament-manager --server.port=9090

Override the database location (default: ~/.tournament-manager/db/tm):

   export TM_DB_PATH=/path/to/your/db/tm
   ./bin/tournament-manager

Print startup diagnostics:

   ./bin/tournament-manager --verbose


Archive layout
--------------

  tournament-manager-<version>/
  ├── bin/
  │   ├── tournament-manager          Unix launcher (chmod +x if extracted from zip)
  │   └── tournament-manager.bat      Windows launcher
  ├── runtime/                         Bundled JRE (Java 21 custom image via jlink)
  ├── lib/                             Application JAR + runtime dependencies
  └── README.txt                       This file


Support
-------

Project: https://github.com/vvwt/vvwt-prj
