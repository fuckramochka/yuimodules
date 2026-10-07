# yuimodules — хот-модулі Yumigram

Окремі розширення клієнта. Клієнт підтягує їх без оновлення APK:
каталог `modules.json` → кнопка «Оновити» → `.hmod` з `hmods/` за секунду.

## Структура

```
modules/<id>/
  manifest.json    опис модуля, версія, що змінилось, entry-клас
  *.java           код модуля (один або декілька файлів)
hmods/             зібрані .hmod (кладе CI, руками не чіпати)
hot-api/           стабільний ABI (копія з клієнта)
modules.json       каталог: branches {stable, beta} + history
```

## Як випустити оновлення модуля

1. Правиш `.java` в `modules/<id>/`.
2. В `modules/<id>/manifest.json` піднімаєш `version` і пишеш `changelog`.
3. Пушиш в `main` — CI збирає dex, кладе `hmods/<id>-<version>.hmod`,
   вписує `sha256` в `modules.json`, стару stable-версію складає в `history`.
4. Користувачі тиснуть «Оновити» в каталозі — все.

Ніяких релізів руками: бінарники комітяться прямо в `hmods/`.

## Поля вітрини (читає магазин клієнта)

`modules/<id>/manifest.json` додатково несе:

- `author`, `category` (`privacy|media|power|custom|other`),
  `featured` (карусель «Вибір редакції»), `permissions`
  (`hook_net|hook_ui|storage|network|background`).
- CI при публікації переносить їх у `modules.json` + дописує `sizeBytes`.

Без підписів каталог працює на SHA-256 (деталка так і пише).
Локальна перевірка: `python3 tools/lint_manifest.py --catalog modules.json`.
