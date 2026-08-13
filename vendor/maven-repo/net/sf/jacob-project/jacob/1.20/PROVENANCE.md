# Provenance: net.sf.jacob-project:jacob:1.20

- Source: official `freemansoft/jacob-project` GitHub repository (successor of the original
  SourceForge JACOB project), release tag `Root_B-1_20` ("Release 1.20", published 2020-09-25).
- Release page: https://github.com/freemansoft/jacob-project/releases/tag/Root_B-1_20
- Downloaded asset: `jacob-1.20.zip`
  (https://github.com/freemansoft/jacob-project/releases/download/Root_B-1_20/jacob-1.20.zip)
- Only `jacob-1.20/jacob.jar` and `jacob-1.20/LICENSE.TXT` were extracted from that zip and
  vendored here as `jacob-1.20.jar` / `LICENSE.TXT`. The native DLLs
  (`jacob-1.20-x64.dll`, `jacob-1.20-x86.dll`) contained in the same zip were deliberately
  NOT extracted or committed — native runtime must be provided by the target environment.
- SHA-256 of `jacob-1.20.jar` (== SHA-256 of `jacob-1.20/jacob.jar` inside the official zip):
  `af2e12cab07343338398d335aa1022bab49376a370792e85c5bbc17a48e62cf1`
- Version confirmed via jar manifest: `Implementation-Version: 1.20 build 01 on
  25-September-2020 06:15:51`.
- License: GNU Lesser General Public License v2.1 (see `LICENSE.TXT`, copied verbatim from the
  official release zip, redistributed alongside the binary as required by LGPL).
- This directory acts as a minimal project-local Maven repository (`file://` layout) so that
  `net.sf.jacob-project:jacob:1.20` resolves without any `mvn install:install-file` step or a
  systemPath dependency. `jacob-1.20.pom` is a minimal hand-written POM (JACOB has no
  transitive dependencies).
