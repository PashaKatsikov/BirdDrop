# AGENT PORT PLAYBOOK — Kotlin

> **Кому.** Этот файл читает AI-агент, которому поручили сделать
> новый порт из `foollegends_template` (нативный Kotlin, не
> Flutter). Пользователь на входе даёт бриф клиента (партнёр,
> оффер-URL, целевые страны). Всё остальное агент собирает и
> валидирует сам по этому файлу.
>
> **Инвариант.** Готовый проект не должен иметь ни одного
> измеримого совпадения ни с одним из соседей в
> `C:\Dev\flutter_projects\`. Меряем по тем же примитивам,
> которыми работает сканер магазина: точное совпадение строк,
> SHA-256 по ассетам, кортеж пинов зависимостей, форма графа
> вызовов.
>
> **Что уже описано.** Механизм генерации уникальности —
> `.cursor/rules/kotlin_fingerprint.mdc`. Что кластеризует
> портфель на уровне оператора —
> `.cursor/rules/play_moderation_hardening.mdc`. Стадии сборки
> нового проекта — `.cursor/DEV_PLAYBOOK.md`. Ручные проверки
> перед сабмитом — `.cursor/FINAL_CHECKLIST.md`.
>
> Здесь — процесс: как агенту порт сделать и как убедиться, что
> антифрод не сработает.

---

## 0. Терминология и режим работы

- **Портфель** — все подпапки `C:\Dev\flutter_projects\*`.
  Портфель **смешанный**, и это принципиально:
  - **Kotlin-сиблы** — есть `app\build.gradle.kts` в корне
    (`BirdDrop`, `BlockBalance`, `CrystalOlympus`, `foollegends`,
    `foollegends_template`);
  - **Flutter-сиблы** — есть `pubspec.yaml` (остальные ~40).

  Строковые проверки для двух стеков разные, но **ассеты,
  домены, keystore, Firebase-проекты, AppsFlyer-ключи и
  applicationId кластеризуются независимо от фреймворка**.
  Инвентаризация ходит по обоим стекам; грепы по исходникам —
  только по Kotlin-сиблам.
- **Сибл** (sibling) — любой ранее отправленный в стор проект.
- **Фингерпринт** — измеримое совпадение с сиблом; всё, что даёт
  сканеру ребро в графе связей.
- **Antifraud-риск** — совокупность признаков, из-за которых
  стор при первой же проверке ставит "high risk" или "waiting
  for review" на несколько суток.
- **Decoy / мусор** — фичи, вводимые сознательно, чтобы
  разбавить структуру и увести форму графа в сторону от
  шаблона. **Пустой decoy (мёртвый код) не работает** — в Kotlin
  его выкидывает R8 на релизной сборке, а если удержать
  `-keep`-правилом, получится класс, который присутствует в DEX
  и ни разу не вызывается: отдельный маркер «функция-приманка».
  Работает только живой decoy: реальные функции белой игры,
  реально дёргаемые в UI (см. §5).

Агент выполняет шаги по порядку. Каждый шаг заканчивается
явным чек-листом (машиночитаемый — используй чек-боксы). Если
хоть один пункт красный — не переходи к следующему шагу.

### 0.1 Две ловушки этого портфеля

Прежде чем запускать скрипты, учти два факта, иначе все замеры
соврут:

1. **Проект может лежать в портфеле дважды.** Рабочая копия
   `BirdDrop` живёт в `C:\bird\drop\game\BirdDrop`, а её копия —
   в `C:\Dev\flutter_projects\BirdDrop`. Скрипты исключают
   текущий проект **по имени папки**, а не по текущему каталогу.
   Во всех сниппетах ниже это переменная `$cur` (путь рабочей
   копии) и `$curName` (имя, которое надо вырезать из портфеля).
2. **Обход портфеля рекурсией занимает ~3 минуты** — там лежат
   `build/`, `.dart_tool/` и `.gradle` соседей. Листинг ассетов
   кэшируется один раз в `_portfolio_assets.csv` (§1.4), дальше
   все проверки читают кэш (1,6 с вместо 171 с).
3. **В рабочей копии может не быть секретов.** `gray.properties`,
   `app/google-services.json`, `keystore.properties` и `*.jks`
   в `.gitignore`, поэтому чистый клон их не содержит — в
   `C:\bird\drop\game\BirdDrop` сейчас нет ни одного из них.
   Без `gray.properties` сборка не падает: она берёт дефолты
   шаблона, печатает warning, секреты резолвятся в пустоту и
   приложение открывает белую часть. Значит `gradlew grayReport`
   в такой копии покажет **шаблонный** кортеж, а не проектный.
   Все проверки §2 и §6.5 запускай в той копии, где секреты
   лежат, и не принимай их вывод за проектный фингерпринт.

Общая шапка для всех сниппетов этого файла:

```powershell
$p       = 'C:\Dev\flutter_projects'
$cur     = 'C:\bird\drop\game\BirdDrop'   # рабочая копия текущего проекта
$curName = 'BirdDrop'                     # как проект называется внутри портфеля
$skip    = '\\(build|\.dart_tool|\.git|\.gradle|\.checkpoints)\\'
# полный путь → имя проекта. Вычислять ОТДЕЛЬНОЙ переменной:
# запись вида `-replace [regex]::Escape($p) + '...','$1'` PowerShell
# разбирает иначе, чем выглядит, и молча возвращает путь целиком —
# счётчики сиблов после этого завышены.
$rx      = '^' + [regex]::Escape($p) + '\\([^\\]+).*'
```

---

## 1. Инвентаризация портфеля (обязательный первый шаг)

Собери фон, чтобы знать, какие значения занять нельзя. Все
команды в PowerShell, из корня текущего проекта.

### 1.1 Занятые идентификаторы

```powershell
# applicationId / namespace обоих стеков — чтобы не столкнуться
# и не повторить схему нейминга
$ids = @()
$ids += Get-ChildItem $p -Directory | ForEach-Object {
  $g = Join-Path $_.FullName 'app\build.gradle.kts'
  if (Test-Path $g) {
    $m = Select-String -Path $g -Pattern 'namespace\s*=\s*"([^"]+)"' | Select-Object -First 1
    if ($m) { "{0}`t{1}`tkotlin" -f $_.Name, $m.Matches[0].Groups[1].Value }
  }
}
$ids += Get-ChildItem $p -Recurse -File -Include build.gradle,build.gradle.kts -EA SilentlyContinue |
  Where-Object { $_.FullName -match '\\android\\app\\' -and $_.FullName -notmatch $skip } |
  ForEach-Object {
    $m = Select-String -Path $_.FullName -Pattern 'applicationId\s*=?\s*"([^"]+)"' -EA SilentlyContinue
    if ($m) {
      $proj = $_.FullName -replace $rx,'$1'
      "{0}`t{1}`tflutter" -f $proj, $m.Matches[0].Groups[1].Value
    }
  }
$ids | Sort-Object -Unique | Out-File .\_portfolio_appids.txt

# rootProject.name — Kotlin-аналог pubspec `name`. Тоже уезжает
# в AAB и в имя каталога сборки.
Get-ChildItem $p -Directory | ForEach-Object {
  $s = Join-Path $_.FullName 'settings.gradle.kts'
  if (Test-Path $s) {
    $m = Select-String -Path $s -Pattern 'rootProject\.name\s*=\s*"([^"]+)"'
    if ($m) { "{0}`t{1}" -f $_.Name, $m.Matches[0].Groups[1].Value }
  }
} | Sort-Object -Unique | Out-File .\_portfolio_slugs.txt
```

Первый блок обходит `android/app` всех Flutter-соседей рекурсией
и занимает ~2 минуты. Результат — 42 строки.

Посмотри на него глазами, а не только грепом: весь портфель
собран по одной схеме `com.<выдуманное-составное-слово>.<имя-игры>`
(`com.aegisthund.aegisthunder`, `com.anubisgate.anubisgategame`,
`com.volcjump.volcanojump`, `com.birddrop.birddropgame`). Само
по себе ни одно значение не совпадает, но **схема** — это тоже
ребро в графе, и оно покрывает весь портфель разом. Новый
`applicationId` не должен её продолжать.

### 1.2 Занятые сиды, кодек-варианты, темы ребренда

В Kotlin-шаблоне нет `forge.lock`: идентифицирующий кортеж
задаётся `gray.properties` (gitignored, но у соседей он лежит
на диске) и выводится `gradlew grayReport`.

```powershell
# Сиды и кодек-варианты соседей. gray.seed НЕ ПОВТОРЯТЬ никогда;
# gray.codecVariant ротировать по кругу 1 → 2 → 3.
Get-ChildItem $p -Directory | ForEach-Object {
  $gp = Join-Path $_.FullName 'gray.properties'
  if (Test-Path $gp) {
    $seed = Select-String -Path $gp -Pattern '^\s*gray\.seed\s*=\s*(\S+)'
    $cv   = Select-String -Path $gp -Pattern '^\s*gray\.codecVariant\s*=\s*(\S+)'
    "{0,-22} seed={1} codec={2}" -f $_.Name,
      $(if($seed){$seed.Matches[0].Groups[1].Value}),
      $(if($cv){$cv.Matches[0].Groups[1].Value})
  }
} | Tee-Object .\_portfolio_seeds.txt

# Занятые темы rebrand.py — определяются по имени класса-роутера
foreach ($t in @(@('canyon','GorgeRouter'),@('reef','TideRouter'),
                 @('orbit','LaunchGate'),@('НЕ РЕБРЕНДИЛИ','WelcomePortal'))) {
  $hits = rg -l --no-messages -F $t[1] $p -g '*.kt' 2>$null |
    ForEach-Object { $_ -replace $rx,'$1' } | Sort-Object -Unique
  "{0,-14} -> {1}" -f $t[0], ($hits -join ', ')
}
```

Правила по результату:

- `gray.seed` — совпадений быть не может в принципе; если есть,
  значит кто-то скопировал `gray.properties` вместе с проектом.
  Это авария: два приложения делят **все** производные значения.
- `gray.codecVariant` — пустое значение означает «вывести из
  сида», и это правильный дефолт ровно до первого столкновения.
  Вариантов всего три, поэтому на портфеле из пяти проектов вывод
  сталкивается сам с собой. Замер: все три соседних
  `gray.properties` (`BirdDrop`-снапшот, `BlockBalance`,
  `CrystalOlympus`) держат явную `1`, и сид этого проекта тоже
  выводит `1` — поэтому здесь прописано явное `2`. Сверь вывод
  `gradlew grayReport` (строка `codec variant`) с соседями и,
  если совпало, пропиши явное значение из свободных.
- Тема `rebrand.py` — не должна совпадать с последними двумя
  соседями. Если все три темы (`canyon`/`reef`/`orbit`) заняты,
  добавь новую в `THEMES` внутри `tools/rebrand.py`, а не
  переиспользуй.

### 1.3 Общий фон строковых литералов

```powershell
$markers = @(
  # универсальные (оба стека)
  'Mozilla/5.0','appid/','appname/','gcdsdk.appsflyer.com',
  'install_data/v4.0','firebase_project_id',
  # тайминги, которые обязаны быть производными от сида
  '259_200','259200','604800',
  # имена шаблона Kotlin
  'com.example.grayshell','WelcomePortal','StreamPortal','AlertPortal',
  'OfflinePortal','TrackingDispatch','ReachDispatch','DataVault',
  'NativeContentActivity','GameHost',
  # ресурсы и темы
  'Theme.App.FullScreen','Theme.App.Stream','ic_notif_flame',
  'network_security_config'
)
$rows = foreach ($m in $markers) {
  $hits = rg -l --no-messages -g '!build/**' -g '!.git/**' -g '!.dart_tool/**' `
          -F $m $p 2>$null |
    ForEach-Object { $_ -replace $rx,'$1' } | Sort-Object -Unique
  [pscustomobject]@{ Marker = $m; Siblings = ($hits|Measure-Object).Count
                     Where = (($hits | Select-Object -First 6) -join ',') }
}
$rows | Sort-Object Siblings -Descending | Format-Table -AutoSize
$rows | Export-Csv -NoTypeInformation -Encoding UTF8 .\_portfolio_markers.csv
```

Ты работаешь **против этого списка**. Любой маркер, что
встречается в ≥ 2 сиблах, должен исчезнуть из нашего проекта
(или мутировать до неузнаваемости).

Три исключения, которые оставляем сознательно:

| Маркер | Почему остаётся |
|---|---|
| `Mozilla/5.0`, `AppleWebKit/537.36`, `Mobile Safari/537.36` | Скаффолдинг UA. Его шлёт каждый честный Chrome; кластеризует не он, а версионный кортеж. Обоснование — `kotlin_user_agent.mdc` §2 |
| `gcdsdk.appsflyer.com`, `install_data/v4.0` | Адрес GCD API. В Kotlin-шаблоне он закодирован в `BuildConfig` из `gray.gcdBase`, в исходниках его быть не должно — если греп нашёл его в `app/src`, это баг |
| `network_security_config` | Имя обязательного ресурса Android |

Остальное — красная зона. Замер на текущем портфеле:
`ic_notif_flame` живёт в 4 проектах (`BirdDrop`-снапшот,
`CandyCraze`, `CrystalOlympus`, `foollegends_template`) — это
ровно тот общий монохромный силуэт, который запрещает
`custom_screens.md` §5; `Theme.App.Stream`, `WelcomePortal` и
`com.example.grayshell` — в 3 каждый.

Имя файла тут вторично, важен силуэт. Из четырёх «пламён» три
байт-в-байт одинаковы (Material `whatshot`), четвёртое
(`CandyCraze`) — стоковое `local_fire_department`. Взять другую
иконку из того же Material-набора значит поменять один
общеизвестный силуэт на другой; глиф надо рисовать. В этом
проекте он теперь `ic_notif_ember` — костёр из трёх языков,
собранный вручную на квадратичных кривых.

Отдельно проверь `259_200`: в
`app/build.gradle.kts` push-snooze записан литералом
`259_200L` с подчёркиванием, поэтому грепы существующих правил
(`rules/kotlin_fingerprint.mdc` §4, они сканируют только
`app/src/main/java`) его **не видят**. По портфелю это значение
встречается в 6 проектах.

### 1.4 Ассеты — что уже светилось

Сначала строим кэш листинга (один раз, ~3 минуты), дальше все
проверки читают его мгновенно.

```powershell
# КЭШ. Пересобирать только когда в портфеле появился новый проект.
Get-ChildItem $p -Recurse -File -Include *.png,*.webp,*.jpg,*.jpeg -EA SilentlyContinue |
  Where-Object { $_.Length -gt 2000 -and $_.FullName -notmatch $skip } |
  Select-Object @{n='Project';e={$_.FullName -replace $rx,'$1'}},
                Name, Length, FullName |
  Export-Csv -NoTypeInformation -Encoding UTF8 .\_portfolio_assets.csv

$assets = Import-Csv .\_portfolio_assets.csv

# Имена, встречающиеся в двух и более проектах
$assets | Group-Object Name |
  Where-Object { ($_.Group.Project | Sort-Object -Unique).Count -ge 2 } |
  Sort-Object { $_.Group.Count } -Descending |
  Select-Object -First 40 @{n='Projects';e={($_.Group.Project|Sort-Object -Unique).Count}}, Name |
  Format-Table -AutoSize

# Точно одинаковые байты. Хешируем только кандидатов, совпавших
# по размеру — иначе прогон занимает десятки минут.
$sizeDupes = $assets | Group-Object Length | Where-Object { $_.Count -gt 1 }
$sizeDupes | ForEach-Object { $_.Group.FullName } | Get-FileHash -Algorithm SHA256 |
  Group-Object Hash | Where-Object { $_.Count -gt 1 } |
  ForEach-Object { "{0} x{1}" -f (Split-Path $_.Group[0].Path -Leaf), $_.Count } |
  Sort-Object
```

### 1.5 Кортеж пинов зависимостей

Flutter-аналог `pubspec.yaml` здесь — блок `dependencies` в
`app/build.gradle.kts`.

```powershell
Get-ChildItem $p -Directory | ForEach-Object {
  $g = Join-Path $_.FullName 'app\build.gradle.kts'
  if (Test-Path $g) {
    Get-Content $g | Select-String -Pattern '^\s*implementation\(' |
      ForEach-Object { $_.Line.Trim() }
  }
} | Group-Object | Where-Object { $_.Count -ge 3 } |
  Sort-Object Count -Descending | ForEach-Object { "{0}  {1}" -f $_.Count, $_.Name } |
  Tee-Object .\_portfolio_pins.txt
```

Фон, из которого этот проект уже выведен: `okhttp:4.12.0`,
`security-crypto:1.1.0-alpha06`, `af-android-sdk:6.16.2`,
`firebase-bom:33.8.0`, `coroutines-android:1.9.0`,
`appcompat:1.7.0`, `installreferrer:2.2` — держатся у 3–4
Kotlin-сиблов построчно. Это готовый кластер, и новый порт
попадёт в него автоматически, если скопировать пины из шаблона.
`installreferrer:2.2` придётся оставить (других живых версий
нет), остальное разводится; §5.1 п.2 о том, как именно.

### Чек-лист шага 1

- [ ] Собраны `_portfolio_appids.txt`, `_portfolio_slugs.txt`,
      `_portfolio_seeds.txt`, `_portfolio_pins.txt`
- [ ] Построен кэш `_portfolio_assets.csv`
- [ ] Составлен маппинг маркер → количество сиблов
      (`_portfolio_markers.csv`)
- [ ] Составлен список ассет-хешей, встречающихся более одного раза
- [ ] Составлен список пинов зависимостей с count ≥ 3
- [ ] Все `_portfolio_*` файлы добавлены в `.gitignore`

---

## 2. Заполнение gray.properties

Flutter-форж собирался из `recipe.json`; в Kotlin роль рецепта
играет `gray.properties`. Открой `gray.properties.example`,
скопируй в `gray.properties` и заполни с учётом фона из §1.

```powershell
gradlew graySeed                                   # свежий сид
Copy-Item gray.properties.example gray.properties
notepad gray.properties
```

### 2.1 Правила подбора значений

| Поле | Правило | Проверка перед прогоном |
|---|---|---|
| `gray.seed` | Никогда не переиспользуется. Только вывод `gradlew graySeed`. Менять после релиза нельзя — уедут имена ключей хранилища и апгрейд увидится как первый запуск | нет в `_portfolio_seeds.txt` |
| `gray.bundleId` | Не в `_portfolio_appids.txt`. **Не** по схеме `com.<выдуманное-составное-слово>.<имя-игры>` — у соседей ровно она (`com.birddrop.birddropgame`, `com.crystalolympus.crystalolympusgame`). Реалистичный reverse-domain как у обычной инди-студии, с другим корнем | grep по портфелю: 0 |
| `rootProject.name` (`settings.gradle.kts`) | Не в `_portfolio_slugs.txt`. Не `template`, не `Gray Shell` | grep: 0 |
| `gray.appLabel` | Совпадает с листингом Play дословно. Не перестановка слов сиблов | ручной осмотр |
| `gray.codecVariant` | По умолчанию **пусто** — тогда вариант выводится из `gray.seed`. `Secrets.kt` реализует все три и выбирает по `BuildConfig.CIPHER_VARIANT`, так что менять код не нужно и сломать ничего нельзя: секреты перекодируются на каждой сборке. Вариантов всего три, поэтому на портфеле из пяти проектов вывод сталкивается — здесь пришлось прописать явную `2` (соседи держат `1`) | §1.2 |
| `gray.configEndpoint` | Домен НЕ на том же WHOIS-регистраторе, что у последних 3 сиблов. Разные TLD между сиблами | ручной осмотр |
| `gray.appsFlyerKey` | Новый ключ из AppsFlyer, никогда не переиспользуется | §7.4 |
| `gray.firebaseProject` | Новый Firebase-проект | §7.4 |
| `gray.gcdBase` | Оставить дефолт — это адрес API AppsFlyer, он у всех один и кодируется в `BuildConfig` | — |
| `gray.allowedHosts` | **Непустой.** Конкретные хосты партнёра, никогда голый TLD. Пустое значение = шелл откроет любой URL, который назовёт сервер | `rg '^gray\.allowedHosts' gray.properties` |
| `gray.oneLinkHost` / `Verify` | Новый поддомен OneLink. Нет кампании — оставить `nolink.invalid` + `false` | §7.4 |
| `gray.uaAppSuffix` | `false`, если партнёр не требует явно. `appid/`/`appname/` встречается в 21–26 проектах портфеля — это самая характерная строка, которую может послать приложение | `rg '^gray\.uaAppSuffix'` |
| `gray.uaAppToken` | Если суффикс всё же включён — токен, не производный от `appLabel` | ручной осмотр |
| `gray.versionCode` / `versionName` | Не `1` / `1.0.0`. Разброс по портфелю обязателен — это ссылочный элемент AAB | ручной осмотр |
| `gray.debugForceUrl` | Пусто перед любой сборкой, которая поедет в стор. В release компилируется в пустую строку принудительно | `rg '^gray\.debugForceUrl'` |

### 2.2 Что подбирать НЕ нужно

Это ключевое отличие от Flutter-форжа: во Flutter-рецепте
`timings.*`, `storage.keyPrefix`, `notifications.channelId`,
`userAgent.chromeVersion` заполнялись руками. **В Kotlin они
выводятся из `gray.seed` детерминированным RNG в
`app/build.gradle.kts` и попадают в `BuildConfig`.** Руками их
не трогают — правка ломает сам механизм
(`kotlin_fingerprint.mdc` §3).

Производится автоматически: имена файлов и всех ключей prefs,
JS-сентинелы, имя JS-моста, id и заголовок FCM-канала,
параметры XOR-кодека, десять таймингов, бюджет редиректов,
версионный кортеж Chrome, закодированные секреты.

Но три вещи производный механизм на сегодня **не** покрывает —
проверь их руками:

1. **`pushSnoozeSeconds`** в `app/build.gradle.kts` записан
   литералом `259_200L` с комментарием «project decision», хотя
   `kotlin_fingerprint.mdc` требует seeded-значение в диапазоне
   2–7 дней. По портфелю `259200` встречается в 6 проектах.
2. **`CHROME_MAJOR`** в `store/UserAgent.kt` — константа `149`,
   а build/patch берутся из `Random` в рантайме. Поля
   `BuildConfig.UA_CHROME_MAJOR/BUILD/PATCH` при этом
   генерируются и не используются. Сверь мажор с соседями
   вручную.
3. **`fcmChannelTitle`** выбирается из захардкоженного списка
   восьми слов (`Promotions`, `Bonuses`, `Updates`, `Offers`,
   `Announcements`, `Rewards`, `Deals`, `News`). На портфеле из
   пяти Kotlin-проектов совпадение вероятно — проверь
   `gradlew grayReport` против соседей и при столкновении
   ротируй сид.

### 2.3 Обязательный dry-run

`mint.dart --dry-run` здесь заменяют две команды:

```powershell
gradlew grayReport            # печатает весь производный кортеж
python tools\rebrand.py       # без --apply печатает только план
```

Сверь вывод `grayReport` построчно с отчётами соседей. Если
хоть одна строка совпала — сид переиспользован, генерируй
новый. Не пытайся обойти столкновение правкой
`_portfolio_*`-файлов: меняй значение в `gray.properties`.

### Чек-лист шага 2

- [ ] Каждое поле `gray.properties` проверено против §1
- [ ] `gradlew grayReport` отработал, ни одна строка не совпадает
      с отчётом любого сибла
- [ ] `python tools\rebrand.py` (без `--apply`) напечатал вменяемый
      план: тема не занята, пакет новый
- [ ] Три пункта §2.2 проверены вручную
- [ ] Ни один WARNING не проигнорирован — на каждый есть решение
      (либо исправлено, либо задокументировано, почему принято)

---

## 3. Прогон ребренда + ручные шаги

`rebrand.py` покрывает больше, чем покрывал Flutter-форж: он
переименовывает и пакет тоже. Но не всё.

### 3.1 Автомат

```powershell
python tools\rebrand.py --apply
# или с явной темой и пакетом:
python tools\rebrand.py --apply --package com.acme.reef --theme reef
```

Скрипт правит: `app/build.gradle.kts`, `app/proguard-rules.pro`,
`AndroidManifest.xml`, `res/values*/*.xml`, `res/xml/*.xml`,
`res/drawable*/**/*.xml`, все `app/src/main/java/**/*.kt`,
`settings.gradle.kts`, `gray.properties(.example)`, физически
переносит `.kt`-файлы в новые пакеты и переименовывает
drawable с префиксом.

**Ограничение, о котором надо знать заранее:** `CURRENT` внутри
`tools/rebrand.py` захардкожен на состояние шаблона
(`com.example.grayshell`, классы `WelcomePortal`/`StreamPortal`/…).
Скрипт рассчитан на **один** прогон из свежей копии шаблона. На
уже отребренженном проекте он не найдёт что менять и тихо
ничего не сделает. Повторная ротация формы кода — это либо
новый порт из шаблона, либо правка `CURRENT` под текущие имена.

### 3.2 Ручное — обязательное

1. **`google-services.json`** — реальный файл из свежего
   Firebase-проекта, положить в `app/`. `package_name` внутри =
   `gray.bundleId`. Файл в `.gitignore`, это нормально.
2. **Keystore.** Новый `.jks`, новый alias, новые пароли,
   `keystore.properties` в корне. Ни один сибл не должен его
   использовать: отпечаток сертификата подписи — первичный ключ
   Google Play для склейки аккаунтов.
3. **Иконка уведомлений.** `rebrand.py` не трогает
   `ic_notif_flame` и две ссылки на него — в манифесте
   (`default_notification_icon`) и в `showNotification()`
   FCM-сервиса. Перерисовать глиф и обновить обе ссылки
   (`custom_screens.md` §5).
4. **Замена ассетов** (см. §4).
5. **Белая часть.** `GameHost` / `NativeContentActivity` в
   шаблоне — заглушка. Должна стать реальной игрой
   (`DEV_PLAYBOOK.md` Stage 3). Если игра уже существует в своём
   репозитории — портируй серую часть **в неё**, а не наоборот,
   и обязательно до прогона `rebrand.py`: переименователь
   текстово проходит по всем `.kt` и перепишет одноимённые
   классы игры (`Fullscreen`, `LoadingView`) из-под неё.
6. **Маршрут назад в игру** из WebView-шелла — убирает сигнал
   «недостижимое поддерево» (`play_moderation_hardening.mdc` §3a).

### 3.3 Проверка после ребренда

```powershell
rg -n 'com\.example\.grayshell' app                       # пусто
rg -n 'android:name="\.' app\src\main\AndroidManifest.xml # каждый пакет существует на диске
gradlew clean assembleDebug                               # собирается после переноса
```

Второй греп не формальность: манифест называет компоненты
относительно namespace (`android:name=".boot.CanyonShell"`), и
исторически именно там переименователь оставлял ссылки на
несуществующие пакеты — сборка проходила, приложение падало на
старте с `ClassNotFoundException` (pitfalls #34).

### Чек-лист шага 3

- [ ] `python tools\rebrand.py --apply` завершился без ошибок
- [ ] `gradlew grayReport` сохранён рядом с проектом как запись
      идентифицирующего кортежа (не коммитить)
- [ ] `com.example.grayshell` не находится ни в одном файле `app/`
- [ ] Каждый `android:name=".…"` из манифеста существует на диске
- [ ] `google-services.json` реальный, `package_name` совпадает
- [ ] `keystore.properties` + `.jks` новые, не переиспользуемые
- [ ] `gray.versionCode` / `versionName` не дефолтные
- [ ] Глиф уведомлений перерисован, обе ссылки обновлены
- [ ] `gradlew clean assembleDebug` проходит

---

## 4. Ассеты — самая громкая зона (не пропускать)

Замеры по портфелю показывают, что игровая графика и иконки
уходят в стор байт-идентичными пачками. Это ловится обычным
`SELECT ... GROUP BY sha256`, никакая нейронка не нужна.

### 4.1 Что нужно сделать с ассетами шаблона

Файлы шаблона (после ребренда префикс `gray_` заменён на
тематический — `cn_`, `reef_`, `orb_`):

- `res/drawable/<prefix>_loading_portrait|landscape.webp`
- `res/drawable/<prefix>_nowifi_portrait|landscape.webp`
- `res/drawable/<prefix>_notif_portrait|landscape.webp`
- `res/drawable/<prefix>_btn_accept|skip.xml`
- `res/drawable-nodpi/*` — арт белой игры, логотип
- `res/mipmap-*/ic_launcher*.png`
- `res/drawable/ic_notif_*.xml`

Для каждого:

1. **Новые байты.** Не пере-сохранение в другом качестве, а
   реально другая картинка. Пере-сохранение бьёт SHA-256, но
   pHash остаётся близким — сканер магазина считает pHash.
2. **Новое имя файла.** Префикс ротирует `rebrand.py`, но
   хвост (`_loading_portrait`) у всех проектов одинаковый.
   Схема имён: короткие, тематические под белую игру, без слов
   `loading`/`nowifi`/`notif`.
3. **Обновить ссылки в Kotlin и XML.** В отличие от Flutter,
   здесь нет манифеста ассетов — ресурсы резолвятся по имени:
   ```powershell
   rg -n 'R\.drawable\.\w+' app\src\main\java
   rg -n '@drawable/\w+' app\src\main\res
   ```
   Переименовал файл — почини обе стороны, иначе сборка упадёт
   на линковке ресурсов (это лучший случай; хуже, когда имя
   собирается динамически — в игре есть
   `drawableName`-строки в `game/model/`, их компилятор не
   проверит).
4. **Шейпы тоже фингерпринт.** `<prefix>_btn_accept|skip.xml`,
   `bg_*.xml` — это `<shape>`-drawable. Два приложения с
   одинаковым градиентом и радиусом угла для статического
   сканера одинаковы. Перекрасить и изменить геометрию.

### 4.2 Иконка приложения и уведомлений

- Adaptive icon: свежая генерация из уникального исходника
  1024×1024 (`tools/make_adaptive_icon.ps1`,
  `tools/preview_icon.ps1`). Марка внутри 66dp safe zone.
- `res/drawable/ic_notif_*.xml` — новый вектор, alpha-only,
  силуэт ДРУГОЙ, чем у launcher icon (`custom_screens.md` §4–5).
  Брать готовый глиф из Material нельзя: соседи уже разобрали
  `whatshot` и `local_fire_department`, а замена одного
  общеизвестного силуэта на другой ничего не решает. Отрисовать
  результат до сборки — `tools/preview_notif_icon.ps1` (кладёт в
  `build/notif_preview.png` три размера: 192/48/24).
- Иконка должна выглядеть как **белая игра**, не как бренд
  партнёра: это первое, что ревьюер сверяет с листингом.

### 4.3 Проверка

```powershell
$assets = Import-Csv .\_portfolio_assets.csv
$mine = Get-ChildItem "$cur\app\src\main\res" -Recurse -File `
          -Include *.png,*.webp,*.jpg -EA SilentlyContinue |
        Where-Object Length -gt 2000
$sizes = @{}; $mine | ForEach-Object { $sizes[[int64]$_.Length] = $true }

$cand = $assets | Where-Object {
  $_.Project -ne $curName -and $sizes.ContainsKey([int64]$_.Length)
}
"size-matched candidates: $(($cand|Measure-Object).Count)"

if ($cand) {
  $ch = $cand.FullName | Get-FileHash -Algorithm SHA256
  $mine | Get-FileHash -Algorithm SHA256 |
    Where-Object { $ch.Hash -contains $_.Hash } |
    ForEach-Object {
      $h = $_.Hash
      "DUP: {0} -> {1}" -f (Split-Path $_.Path -Leaf),
        (($ch | Where-Object Hash -eq $h |
          ForEach-Object { $_.Path -replace $rx,'$1' } |
          Sort-Object -Unique) -join ',')
    }
}
# должно быть пусто
```

### Чек-лист шага 4

- [ ] Ни одно имя вида `<prefix>_loading_portrait` /
      `<prefix>_nowifi_*` / `<prefix>_notif_*` не осталось
- [ ] SHA-256 всех наших ассетов уникален относительно портфеля
- [ ] Иконка сгенерирована из нового исходника, марка в safe zone
- [ ] Notification-icon перерисован, alpha-only, силуэт отличается
      от launcher-icon
- [ ] `<shape>`-drawable перекрашены и перегеометрены
- [ ] `R.drawable.*` и `@drawable/*` перепрописаны под новые имена,
      `gradlew assembleDebug` проходит

---

## 5. «Мусор» для отвлечения — что реально работает

**Мёртвый decoy не работает.** В релизной сборке R8 удалит
класс, недостижимый из точек входа, по tree-shaking. Если
удержать его `-keep`-правилом в `proguard-rules.pro`, получится
хуже исходного: класс лежит в DEX и никогда не вызывается —
готовый маркер «функция-приманка». Не делай так.

**Работает — живая функциональность белой игры.** Её код
попадает в call graph, R8 стирает имена, но структура графа
становится **непохожей** на шаблон. Именно это меняет
эмбеддинг проекта в кластеризаторе.

### 5.1 Что добавлять (в порядке эффективности)

1. **Реальные фичи белой игры.** Из портфеля видно, что почти
   все проекты — «tower / block / bounce / plinko / gravity» с
   идентичным набором экранов. Возьми одну-две **не типичные
   для портфеля** фичи и реально реализуй:
   - локальный leaderboard на 10–20 записей (`SharedPreferences`);
   - настройки со слайдерами (громкость, вибрация, тема);
   - магазин косметики (скины, покупки за игровую валюту);
   - achievements через `play-services-games-v2` (реальная зависимость);
   - tutorial-режим с несколькими шагами и анимацией руки;
   - daily reward календарь.

   Каждая такая фича — новые Kotlin-файлы, новые Activity или
   фрагменты, новые ключи в `GamePrefs`, новые layout'ы. Форма
   графа расходится с сиблами, а не сходится.

2. **Разные пакеты зависимостей.** Развести версии — это
   необходимый минимум, и он уже сделан (§1.5), но одна лишь
   смена цифр оставляет тот же **набор** артефактов. Замени
   часть там, где семантически равнозначно:
   - `okhttp` → `HttpURLConnection` (конфиг-POST и GCD-GET —
     это два запроса, ради них тянуть клиент не обязательно)
     или Ktor;
   - `androidx.security:security-crypto` → собственная обёртка
     над `AndroidKeyStore` + `EncryptedFile`. Отдельный повод:
     начиная со стабильной `1.1.0` Google пометил
     `EncryptedSharedPreferences` и `MasterKey` как deprecated,
     так что уходить оттуда придётся в любом случае;
   - `firebase-analytics` → выкинуть, если аналитика не
     используется: лишняя зависимость и лишний permission-профиль.
     В коде этого проекта обращений к Analytics нет — она
     подключена «на всякий случай».

   Такая замена меняет call graph, а не только строку в
   `build.gradle.kts`, и это единственное, что выводит из
   кластера по-настоящему.

   Плюс **разведи версии остального**: `FINAL_CHECKLIST.md` §1
   требует минимум двух отличий от предыдущего проекта. Сейчас
   их ноль.

3. **Другая структура пакетов.** `rebrand.py` ротирует имена
   папок из своего word bank, но **количество и назначение**
   пакетов одинаковы во всех темах: восемь штук, ровно по
   ролям. Разбей иначе — слей два пакета, вынеси третий,
   положи часть кода в корень.

4. **Реальные экраны, не только WebView.** Меню, магазин,
   настройки, tutorial. Не заглушки — рабочие. Приложение, у
   которого в сессии > 90% времени один `WebView`, выглядит
   ровно как обёртка.

5. **Разный splash / боот-экран.** Не только цвет фона. Разное
   количество кадров анимации, разный порядок этапов
   инициализации в `Application.onCreate`.

### 5.2 Чего НЕ делать (мнимый decoy)

- Пустые классы «на будущее», удерживаемые `-keep`-правилом.
- Комментарии-заглушки типа `// TODO: implement analytics`.
- Подключение зависимостей, которые нигде не вызываются
  (`implementation` без единого использования).
- Копирование чужого кода из open-source без встраивания в
  call graph (сканер найдёт по эмбеддингу оригинал и добавит
  ребро к нему).
- «Разные обёртки над одним и тем же». Если `PrefStore` /
  `DataVault` / `SafeVault` делают ровно одно и то же — они
  схлопнутся при нормализации.

### 5.3 Проверка живости decoy

```powershell
# Kotlin-файлы, на чей top-level класс нет ни одной ссылки в проекте.
# Эвристика грубая (не видит рефлексию и XML-ссылки), но
# кандидатов на удаление находит.
Get-ChildItem "$cur\app\src\main\java" -Recurse -Filter *.kt -File | ForEach-Object {
  $name = $_.BaseName
  $refs = (rg -c --no-messages -w $name "$cur\app\src\main" 2>$null |
           Measure-Object).Count
  # 1 = только собственное объявление
  if ($refs -le 1) { "UNUSED?: $($_.FullName.Substring($cur.Length+1))  refs=$refs" }
}

# Классы, которые держит только -keep
rg -n '^-keep' "$cur\app\proguard-rules.pro"
```

Каждый `UNUSED?` — либо удалить, либо объяснить (Activity,
Service и `@JavascriptInterface`-мосты ожидаемо всплывают:
на них ссылается манифест или JS, а не Kotlin).

### Чек-лист шага 5

- [ ] Как минимум 2 «живые» белые фичи, отсутствующие у
      большинства сиблов, реализованы и достижимы из точки входа
- [ ] Кортеж пинов зависимостей отличается от Kotlin-соседей
      минимум в двух позициях (замена пакета, не только патч)
- [ ] `UNUSED?: …` разобран: ноль либо список с объяснением
- [ ] `-keep`-правила покрывают только реально нужное
      (Firebase / AppsFlyer / OkHttp / JS-мост / точки входа)
- [ ] Session-профиль тестового прогона: время вне WebView ≥ 30%
      (пройди до WebView вручную через реальные экраны)

---

## 6. Верификация фингерпринта перед сборкой

Гейт: **все** пункты должны быть зелёные. Если хоть один
красный — не собирай релиз.

### 6.1 Grep-гейты (нулевой хит на каждом)

```powershell
cd $cur

# Производные значения, вписанные руками вместо BuildConfig
rg -n '259_?200|Chrome/1[0-9][0-9]\.|__flsa\b|__kb\b|KbPan|fl_state|fl_enc|app_push_channel' app\src\main\java

# То же самое, но по build-скрипту — существующие правила его не сканируют
rg -n '259_?200|604800' app\build.gradle.kts

# appid/appname в открытую
rg -n 'appid/|appname/' app\src\main\java

# Классификация партнёра — в коде белой игры не должно быть его лексики.
# Границы слова обязательны: без них `register` ловит
# registerForActivityResult и registerDefaultNetworkCallback,
# а `slot` — любой игровой слот в белой части.
rg -n -i '\b(deposit|cashier|casino|slots?|bonus|wager|payout)\b|game_type|воронк|касс' app\src\main\java

# Дефолты шаблона. Скоуп важен: строки "CHANGE-ME-EVERY-PROJECT"
# и "Gray Shell" легально живут в app/build.gradle.kts как
# fallback-значения движка фингерпринта — там их проверять
# бессмысленно, проверять надо фактические значения в gray.properties.
rg -n 'com\.example\.grayshell|WelcomePortal|StreamPortal|NativeContentActivity' app\src\main\java app\src\main\AndroidManifest.xml settings.gradle.kts
rg -n '^\s*gray\.(seed|appLabel|bundleId)\s*=' gray.properties

# Логи мимо Trace — в релизе это утечка и маркер
rg -n 'Log\.(i|w|e|d|v)\(' app\src\main\java | rg -v 'Trace\.kt'

# Второй сборщик User-Agent
rg -n 'Mozilla/5\.0' app\src\main\java        # ровно один хит: store/UserAgent.kt

# Схема window.__ префикса — один regex не должен ловить весь портфель
rg -n 'window\.__\w{2,3}(Sa|Kb|Safe|Video)\w*' app\src\main\java

# Разрешения: ровно четыре
rg -n 'uses-permission' app\src\main\AndroidManifest.xml
```

Ожидания: все грепы — ноль хитов, кроме `Mozilla/5.0` (ровно
один файл), `gray.properties` (три заполненные строки) и
`uses-permission` (ровно `INTERNET`, `ACCESS_NETWORK_STATE`,
`POST_NOTIFICATIONS`, `VIBRATE`).

Две оговорки, иначе будешь чинить несуществующее:

- **`Log.*`, закрытый ранним выходом по `BuildConfig.DEBUG`, —
  не нарушение.** `BuildConfig.DEBUG` компилируется в
  константу, R8 сворачивает ветку целиком, и в релизном DEX от
  вызова не остаётся ничего. Такой код в белой игре есть
  (`game/GameView.kt`, счётчик кадров). Греп его показывает —
  это нормально; настоящий гейт здесь §6.6 по DEX.
- **Комментарии считаются.** Строка `appid/` в KDoc не попадёт
  в DEX, но попадёт в исходники, которые могут уехать в
  репозиторий. Чистим и их: сейчас `appid/` встречается в
  `store/UserAgent.kt` (KDoc + рабочая ветка под флагом) и
  `boot/CanyonShell.kt` (KDoc).

### 6.2 Сравнение с портфелем

Flutter-версия сравнивала `lib`; здесь сравниваем
`app/src/main/java` только с Kotlin-сиблами — у Flutter-соседей
строки живут в Dart и пересекаться будут только по
универсальным маркерам из §1.3.

```powershell
$kotlinSibs = Get-ChildItem $p -Directory |
  Where-Object { $_.Name -ne $curName -and (Test-Path (Join-Path $_.FullName 'app\src\main\java')) } |
  ForEach-Object { Join-Path $_.FullName 'app\src\main\java' }

$mine = rg -o --no-messages -N '"[A-Za-z0-9_./: -]{10,}"' "$cur\app\src\main\java" |
  ForEach-Object { $_ -replace '^.*?"([^"]+)".*$','$1' } | Sort-Object -Unique

$hits = 0
foreach ($s in $mine) {
  $n = (rg -l --no-messages -F $s @kotlinSibs 2>$null |
        ForEach-Object { $_ -replace $rx,'$1' } |
        Sort-Object -Unique | Measure-Object).Count
  if ($n -ge 3) { "MATCH x${n}: $s"; $hits++ }
}
"total matches across >=3 siblings: $hits"
```

Верхняя граница: **0 строк** с совпадением в ≥ 3 сиблах.

Прогон занимает ~2 минуты на текущем портфеле (около 5,5 тысяч
уникальных строк × четыре Kotlin-сибла) — это нормально, скрипт
не завис. Последний `rg` в цикле возвращает exit code 1, когда
совпадений нет; на результат это не влияет.

### 6.3 Ассеты

Скрипт из §4.3 должен вернуть пусто.

### 6.4 Зависимости

```powershell
$mineDeps = Get-Content "$cur\app\build.gradle.kts" |
  Select-String -Pattern '^\s*implementation\(' | ForEach-Object { $_.Line.Trim() }
Get-ChildItem $p -Directory | Where-Object { $_.Name -ne $curName } | ForEach-Object {
  $g = Join-Path $_.FullName 'app\build.gradle.kts'
  if (Test-Path $g) {
    $sib = Get-Content $g | Select-String -Pattern '^\s*implementation\(' |
           ForEach-Object { $_.Line.Trim() }
    $common = (Compare-Object $mineDeps $sib -IncludeEqual -ExcludeDifferent |
               Measure-Object).Count
    "{0,-22} shared pins: {1} / {2}" -f $_.Name, $common, ($mineDeps|Measure-Object).Count
  }
}
```

Ни с одним соседом не должно быть 100%-го перекрытия; §5.1 п.2
требует минимум двух отличий от предыдущего проекта.

### 6.5 Что осталось в артефакте сборки

Flutter проверял `libapp.so`; в Kotlin эквивалент — DEX внутри
APK. Собери релиз и посмотри, что пережило R8.

```powershell
gradlew assembleRelease
$apk = Get-ChildItem "$cur\app\build\outputs\apk\release" -Filter *.apk | Select-Object -First 1
Remove-Item -Recurse -Force .\_apk_extract -EA SilentlyContinue
Expand-Archive -Force $apk.FullName -DestinationPath .\_apk_extract

# Шаблонные и служебные строки, пережившие минификацию
Get-ChildItem .\_apk_extract -Filter classes*.dex | ForEach-Object {
  rg -a -o -e 'com\.example\.grayshell' -e 'WelcomePortal' -e 'StreamPortal' `
        -e 'GorgeRouter' -e 'CanyonShell' -e 'appid/' -e 'gray\.seed' `
        -e 'flutter_projects' $_.FullName
}

# Секреты обязаны быть закодированы — plaintext-хоста быть не должно.
# Требует настоящий gray.properties (см. §0.1 п.3).
$m = Select-String -Path "$cur\gray.properties" -EA SilentlyContinue `
       -Pattern '^\s*gray\.configEndpoint\s*=\s*(\S+)'
if ($m) {
  $cfgHost = ([uri]$m.Matches[0].Groups[1].Value).Host
  Get-ChildItem .\_apk_extract -Filter classes*.dex |
    ForEach-Object { rg -a -c -F $cfgHost $_.FullName }
} else { "SKIP: gray.properties без configEndpoint — сборка не проектная" }

# mapping-файл существует (его грузим в Play) и НЕ лежит внутри APK
Test-Path "$cur\app\build\outputs\mapping\release\mapping.txt"
Get-ChildItem .\_apk_extract -Recurse -Filter mapping.txt

Remove-Item -Recurse -Force .\_apk_extract
```

Хиты по первому блоку — красная зона: либо `rebrand.py` не
отработал, либо `-keep`-правило держит шаблонный класс. Хит по
хосту означает, что секрет попал в бинарь открытым — проверь,
что значение читается через `Secrets`/`Env`, а не вписано в
Kotlin литералом.

### 6.6 Логи не должны пережить релиз

```powershell
Get-ChildItem .\_apk_extract -Filter classes*.dex | ForEach-Object {
  rg -a -o -e 'SDK started from' -e 'request body composed' -e 'conversion=' $_.FullName
}
```

`Trace` обёрнут в `BuildConfig.DEBUG`, поэтому в release эти
строки должны выпасть вместе с ветками. Если они на месте —
где-то остался прямой `Log.*` (см. §6.1).

### Чек-лист шага 6

- [ ] Все грепы §6.1 — 0 хитов (кроме двух оговорённых)
- [ ] Скрипт §6.2 — 0 строк с покрытием в ≥ 3 сиблах
- [ ] Скрипт §4.3 — 0 совпадений ассетов
- [ ] Скрипт §6.4 — нет 100% перекрытия по зависимостям, есть
      минимум 2 отличия от предыдущего проекта
- [ ] Скрипт §6.5 — 0 шаблонных строк и 0 plaintext-секретов в DEX
- [ ] Скрипт §6.6 — 0 отладочных строк в release-DEX
- [ ] `mapping.txt` существует и не лежит внутри APK

---

## 7. Антифрод / high-risk mitigation

Даже идеальный по фингерпринту проект попадает в
"waiting for review" по внешним признакам. Проверь каждый.

### 7.1 Аккаунт и инфраструктура

- [ ] **Google Play Console** — аккаунт этого портфель-слота,
      не общий. **Никогда** не логинься под несколькими
      аккаунтами с одного IP/устройства в один день.
- [ ] **Payment method** на аккаунте — своя, не повторяет
      других аккаунтов портфеля.
- [ ] **Phone / identity verification** документы —
      разные для разных портфель-слотов. Это Play использует в
      "related accounts" сильнее, чем содержимое APK.
- [ ] **Upload key.** Отпечаток сертификата подписи — жёсткая
      связка. Один `.jks` на одно приложение, Play App Signing
      это не прячет: upload-сертификат записывается.
- [ ] **Build machine / upload IP.** Заливать AAB через VPN,
      желательно разный для разных слотов. Не заливать пять
      AAB подряд с одного IP.

### 7.2 Ритм сабмишенов

- [ ] С момента заливки белого билда до заливки серого — ≥ 3
      дня. Не тот же час.
- [ ] Не заливать одновременно несколько игр из одного слота
      в один день.
- [ ] Первая ASO-обнова после релиза — минимум через 5–7 дней,
      без нового билда (Product Page Optimization). Обновления
      бинаря на «свежий» аккаунт в первые дни — самый частый
      триггер бана.

### 7.3 Content review flags

- [ ] Скриншоты в Play — **только белая игра**. Ни одного
      скрина WebView-контента.
- [ ] Описание Play — на языке белой игры. Ни слова о
      партнёре, «casino», «slots», «deposit».
- [ ] `rootProject.name`, `gray.appLabel`, `app_name` в
      `res/values/strings.xml` и заголовок листинга описывают
      одну и ту же игру:
      ```powershell
      rg -n 'rootProject.name' settings.gradle.kts
      rg -n '^gray\.appLabel' gray.properties
      rg -n 'app_name' app\src\main\res\values\strings.xml
      ```
- [ ] Data Safety — честно то, что собирает приложение:
      Device IDs (AppsFlyer ID, FCM-токен), назначение Analytics
      и Advertising, передача третьим лицам — да. Декларация
      «no data collected» при подключённом AppsFlyer SDK
      проверяется автоматически и является нарушением.
- [ ] Contains ads / IAP флажки — соответствуют белой игре
      реально.
- [ ] Приложение стартует и играется **без интернета** (белая
      часть). Если оффлайн-режим весь уходит на no-wifi экран,
      Play видит «app doesn't work without internet», и для
      казуальной игры это подозрительно.
      Важно: инвариант серой части «UNDECIDED + офлайн → no-wifi
      на первом кадре» этому не противоречит — он про **первый**
      запуск по ссылке. Убедись, что уже решённый NATIVE-режим
      играется в самолётном режиме без единого запроса.

### 7.4 Firebase / AppsFlyer

- [ ] Firebase проект — новый, никогда не переиспользуется.
      `google-services.json` несёт project id, project number и
      API-ключ открытым текстом внутри APK: общий проект — это
      однозначный идентификатор связи.
- [ ] AppsFlyer app — новый, dev key не делится с сиблом.
- [ ] OneLink subdomain — новый.
- [ ] `google-services.json` → `package_name` == `gray.bundleId`
      (иначе Play при сканировании манифеста с включённым FCM
      найдёт mismatch).
- [ ] Никакого `applicationIdSuffix` в debug — ломает атрибуцию
      и заводит второй package id в Firebase-проекте
      (pitfalls #1).

### 7.5 Privacy Policy

- [ ] URL из `res/values/strings.xml` (`privacy_url`) возвращает
      HTTP 200 **сейчас**.
- [ ] Тот же URL зарегистрирован в Play Console.
- [ ] Домен privacy — НЕ на том же WHOIS-registrant, что у
      предыдущих 3 сиблов. Разные регистраторы, разные даты
      регистрации, разные хостинги. Отдельно проверь, что это не
      общая страница на весь портфель: в портфеле уже лежит
      `PrivacyPolicy_BlockBalance` — по этой схеме страница
      привязана к приложению, так и держи.
- [ ] Содержание страницы — реальный privacy policy, а не
      сгенерированный шаблон, идентичный 20 другим.

### Чек-лист шага 7

- [ ] Все пункты §7.1 подтверждены оператором
- [ ] Ритм сабмишенов согласован (никаких «залить сегодня»)
- [ ] Скриншоты, описание, Data Safety проверены
- [ ] Белая часть играется в самолётном режиме
- [ ] Firebase/AppsFlyer/OneLink — новые
- [ ] Privacy URL — HTTP 200, WHOIS отличается

---

## 8. Финальный отчёт агента пользователю

Когда все чек-листы зелёные, выдай в чат ровно это:

```
PORT READY: <gray.appLabel>

Fingerprint:
- applicationId: <...>
- gray.seed (first 6): <...>
- rebrand theme: <...>
- codec variant: <...>
- FCM channel id / title: <...> / <...>
- Chrome UA tuple: <...>

Portfolio diff:
- grayReport lines matching any sibling: 0
- string overlap with Kotlin siblings (>=3): 0
- asset SHA-256 dupes: 0
- dependency pins shared with nearest sibling: <n> / <total>
- template strings in release DEX: none
- plaintext secrets in release DEX: none

Manual steps still owned by operator:
- keystore .jks (fresh): <status>
- google-services.json: <status>
- new Firebase / AppsFlyer / OneLink: <status>
- Play account for this slot: <status>
- privacy URL live: <status>

Awaiting operator signoff on §7 before submission.
```

Пользователь на основе этого решает — заливать или нет.

---

## 9. Ссылки

- Как генерируется уникальность — `.cursor/rules/kotlin_fingerprint.mdc`
- Что кластеризует портфель в Play — `.cursor/rules/play_moderation_hardening.mdc`
- Архитектура и контракт конфига — `.cursor/rules/kotlin_gray_guide.mdc`
- Стейт-машина запуска — `.cursor/rules/kotlin_launch_flow.mdc`
- Контракт WebView-шелла — `.cursor/rules/kotlin_webview.mdc`
- UA-контракт, что грепать — `.cursor/rules/kotlin_user_agent.mdc`
- Контракт артворка — `.cursor/rules/custom_screens.md`
- Известные грабли (37 записей) — `.cursor/rules/kotlin_gray_pitfalls.mdc`
- Стадии сборки нового проекта — `.cursor/DEV_PLAYBOOK.md`
- Ручные проверки перед сабмитом — `.cursor/FINAL_CHECKLIST.md`
- Карта файлов для быстрого поиска — `.cursor/rules/AGENT.md`
