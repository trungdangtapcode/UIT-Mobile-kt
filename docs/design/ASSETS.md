# UIT identity, assets and design references

## Design direction

The interface uses UIT blue, document-like reading surfaces, aligned academic
records and IT-related icons. See the [phone scope](MOBILE.md) and [phone gallery](README.md)
for the current implementation and review targets.

The original UIT lotus/orbital emblem is displayed intact with a transparent
background. A single softly blurred campus photograph sits behind the masthead.
Decorative circuit and orbital fields from the earlier revision were removed.

## Bundled visual assets

| Asset | Source | Treatment |
| --- | --- | --- |
| `uit_campus.jpg` | [UIT admissions: reasons to study at UIT](https://tuyensinh.uit.edu.vn/ly-do-nen-hoc-thac-si-tai-uit), [original photograph](https://tuyensinh.uit.edu.vn/sites/default/files/uploads/images/201809/dai-hoc-uit-3.jpg) | Original JPEG bundled locally; Compose applies a restrained blur and fades the image into the light or dark page background. |
| `uit_emblem.xml` | Existing `uit-cli/docs/landing-page/assets/uit.svg`, checked against the [UIT identity site](https://vi.uit.edu.vn/vi/page/huong-dan-su-dung-logo-truong-dai-hoc-cong-nghe-thong-tin) and its [published emblem](https://www.uit.edu.vn/images/small-logo.png) | Original vector paths and fills translated to Android VectorDrawable. Proportions and emblem colors are preserved; no generated replacement. |
| `uit_official_mark.png` | [UIT public website](https://www.uit.edu.vn/images/small-logo.png) | Unmodified source reference bundled alongside the scalable emblem. |
| IT and navigation icons | Existing Material Icons Extended dependency | Storage, data/code, algorithm tree, processor, network router and terminal icons selected by course domain; calendar, message and status controls retain task-specific icons. |

UIT imagery and identity remain university assets. Their inclusion does not
relicense them under the app's MIT source-code license or imply university
endorsement. See the [UIT logo guidance](https://vi.uit.edu.vn/vi/page/huong-dan-su-dung-logo-truong-dai-hoc-cong-nghe-thong-tin)
for the supplied variants and identity requirements.

The campus photo is decorative context, not a live campus image. On Android
versions supporting Compose blur, the masthead image uses an 8dp blur. Older
versions retain the background fade and reduced image opacity even when the
platform cannot apply the blur.

## Typography

IBM Plex Sans Regular/Medium/SemiBold and IBM Plex Mono Regular are bundled from
[IBM's official Plex repository](https://github.com/IBM/plex). Plex Sans carries
academic content and controls. Plex Mono is reserved for section codes, period
labels, sync timestamps and numerical readouts.

The fonts use the SIL Open Font License; the unmodified license is included at
[licenses/IBM-Plex-OFL.txt](licenses/IBM-Plex-OFL.txt). No font download or third-party
image request is required at runtime. Font and UIT asset notices are also bundled
inside the APK under `assets/licenses/`.

Asset SHA-256 values are recorded in [asset-checksums.json](asset-checksums.json).

## Historical reference and template evaluation

| Reference | Applied guidance |
| --- | --- |
| [UIT identity](https://vi.uit.edu.vn/vi/page/huong-dan-su-dung-logo-truong-dai-hoc-cong-nghe-thong-tin) | Authentic lotus/orbit emblem, blue institutional identity and proper proportions. |
| [Carbon 2x Grid](https://carbondesignsystem.com/guidelines/2x-grid/overview/) | Technical hierarchy, consistent spacing units and predictable data alignment. |
| [Flowbite admin dashboard](https://github.com/themesberg/flowbite-admin-dashboard) | Compact navigation, practical control groups and usable data density. |
| [shadcn dashboard example](https://ui.shadcn.com/examples/dashboard) | Clear separation of navigation, filters, data and actions. |

These references were evaluated during the earlier revision, outside the current
phone-only product direction; the current
composition is also informed by the [visual audit](AI-SLOP-AUDIT.md). The implementation uses the
existing Material 3 Compose primitives and native Android resource pipeline.

## Design skill

[interface-design](https://skills.sh/dammyjay93/interface-design/interface-design)
from [Dammyjay93/interface-design](https://github.com/Dammyjay93/interface-design)
was installed into the user's Codex skills directory and applied to this revision.
The source was checked through its published skill listing and GitHub repository.
Project-specific decisions are recorded in
[.interface-design/system.md](../../.interface-design/system.md).

The skill remains a local development aid and is not an application dependency.
