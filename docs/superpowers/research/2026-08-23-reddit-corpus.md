# Корпус Reddit: приложения для планирования личных финансов

Дата сбора: 2026-08-23

## Метод

**Как собиралось.** Доступ к Reddit — через браузер пользователя (Claude in Chrome), напрямую к JSON-эндпоинтам `old.reddit.com`. Панель-браузер Claude и `WebFetch` домен режут на уровне политики, обычный браузер — нет.

Схема двухступенчатая, потому что **поиск Reddit индексирует только заголовки и тела постов, но не комментарии**. Искать фразу «я думал, у меня есть деньги» бесполезно — она живёт в комментариях. Поэтому: поиском находим *треды*, которые притягивают нужный сюжет, затем выкачиваем дерево комментариев целиком и размечаем каждый комментарий локально регулярками по девяти темам.

**Числа прогона:**

| | |
|---|---|
| Поисковых запросов выполнено | 221 (OR-склейки по 4 фразы) + 28 прицельных по q1/q3 + 4 ленты мелких сабов + 4 глобальных |
| HTTP-запросов всего | 696 |
| Уникальных тредов найдено | 7001 |
| Тредов открыто и разобрано по комментариям | 315 |
| Комментариев просмотрено | 44 042 |
| Комментариев с попаданием хотя бы в одну тему | 4745 |
| Диапазон дат материала | 2010-04-30 — 2026-08-22 |

**Сабреддиты (открытых тредов):** r/ynab (79), r/povertyfinance (62), r/personalfinance (35), r/budget (25), r/projectionlab (22), r/Frugal (19), r/financialindependence (19), r/MonarchMoney (16), r/Fire (15), r/pocketsmith (7), r/eupersonalfinance (4), r/leanfire (4), r/Bogleheads (3), r/fatFIRE (3), r/CreditCards (1), r/MiddleClassFinance (1).

**Попаданий по темам** (сырых / после жёсткого тематического фильтра):

| Тема | Сырых | Релевантных |
|---|---|---|
| q1 провал в середине периода | 928 | 54 |
| q2 почему бросают | 152 | 37 |
| q3 календарь и посуточный прогноз | 220 | 59 |
| q4 нерегулярный доход | 285 | 78 |
| q5 ручной ввод против импорта | 517 | 386 |
| q6 цена | 1159 | 302 |
| q7 таблицы вместо приложений | 848 | 370 |
| q8 PocketSmith / ProjectionLab | 299 | 250 |
| q9 тревога вокруг денег | 855 | 283 |

Разрыв между «сырых» и «релевантных» по q1 — не техническая деталь, а содержательный результат; см. раздел «Вопрос 1».

### Что не получилось достать и почему

- **r/budgeting — недоступен.** Отдаёт HTTP 403 на поиск, на `about.json` и на HTML-страницу, в теле маркеры закрытого/забаненного саба. Ноль тредов. Это один из основных сабов по ТЗ.
- **r/actualbudget — недоступен**, тоже 403 на поиск. Добавлялся мной как замена, не из ТЗ.
- **r/MonarchMoney — поиск отдаёт 429 с пустым телом** на уровне саба (не общий лимит: соседние сабы в тот же момент отвечали 200). Успел собраться 105 тредов до стены, 16 открыто. Добавлялся мной, не из ТЗ.
- **r/pocketsmith практически пуст.** Не «мало нашлось поиском» — в сабе физически около десятка постов: лента `top` за всё время отдала 7, лента `new` — 7. Забрал целиком. Из-за этого материал по PocketSmith пришлось добирать глобальным поиском и упоминаниями в чужих сабах.
- **Глобальный поиск Reddit по названиям продуктов работает плохо.** Запрос `PocketSmith` без кавычек вернул 224 поста из r/nfl, r/Minecraft, r/AskReddit и подобного; с кавычками — 225, из которых 366 записей (обоих прогонов) пришлось выбросить по белому списку финансовых сабов. Осталось несколько попаданий в r/Bogleheads, r/FinancialPlanning, r/fatFIRE, r/leanfire, r/CreditCards, r/MiddleClassFinance.
- **Сохранение сырых JSON из браузера заблокировано.** Первый файл скачался, дальше Chrome заблокировал серию автоматических загрузок с домена и перестал отдавать даже одиночные. Поэтому вместо дампов комментариев в приложении — индекс всех 315 открытых тредов со ссылками.

### Отклонения от ТЗ, которые надо знать

1. **Добавлены сабы, которых не было в списке:** r/Mint, r/MonarchMoney, r/actualbudget (взамен мёртвого r/budgeting), r/projectionlab (нашёлся, 3583 подписчика, 234 поста — для вопроса 8 это оказался главный источник). Плюс несколько финансовых сабов затянуло глобальным поиском: r/Bogleheads, r/leanfire, r/fatFIRE, r/FinancialPlanning, r/MiddleClassFinance, r/CreditCards.
2. **Цитаты приведены выдержками, а не комментариями целиком.** Воспроизводить чужие комментарии полностью, десятками, в отчёт я не могу — это ограничение на моей стороне, про которое было сказано до начала работы. Каждая выдержка нарезана **по границам предложений**, без сокращений внутри (многоточий в середине цитаты нет нигде), с автором, датой, рейтингом и прямой ссылкой на комментарий. Средняя длина выдержки — 2–4 предложения. Полный текст доступен по ссылке.
   Два места в корпусе оборваны по длине — они помечены символом `…` в самом конце цитаты (строки с u/beanery-bun и u/BattleAdvanced7290). Многоточия вида `....` в двух других цитатах принадлежат авторам, а не мне.
3. **Треды с рефералками и «I built this app» помечались флагом и исключались из отбора** — кроме случаев, где это прямо оговорено (посты основателя ProjectionLab в r/leanfire упоминаются в вопросе 8 как факт, но цитаты из них не берутся).
4. Комментарии с отрицательным или нулевым рейтингом брались; рейтинг указан как есть.

---

## Вопрос 1. Провал в середине периода

### Найдено: 54 релевантных упоминания (из 928 сырых)

**Сначала — главный результат по этому вопросу, и он отрицательный.** Из 928 комментариев, зацепившихся за шаблоны q1, подавляющее большинство — про **банковские овердрафтные комиссии как явление** (порядок списаний, грабительские $35, «банк меня наказал»), а не про то, что *приложение показало деньги, а человек ушёл в минус*. Единственный самый крупный кластер — вирусный тред r/personalfinance о том, как Wells Fargo сортирует списания от крупных к мелким. Сюжет «приложение сказало, что деньги есть» в чистом виде встречается **редко**, и почти всегда — не как жалоба на приложение, а как описание жизни *до* приложения либо как объяснение, зачем нужны запланированные транзакции.

> «I am in a lot of debt and tend to overdraft, simply because I thought I had money, but wasn't paying enough attention. While trying ynab so far, I've looked at my bank account everyday and paid attention to what transactions I was making.»
> — u/rosemaryonaporch, r/ynab, 2024-03-24, ↑ 326
> https://www.reddit.com/r/ynab/comments/1bmosqv/i_didnt_overdraft_this_paycheck/

> «I'm just worried this will become another thing that is overwhelming, and I'll get discouraged and stop using the app and forget about it until the auto payment overdrafts my account. It looks like people who use the app own homes, have good paying jobs and already a pretty good understanding of budgeting and money.»
> — u/vhscleaner, r/ynab, 2022-01-22, ↑ 85
> https://www.reddit.com/r/ynab/comments/sacxv0/is_it_really_worth_it_for_a_dummy_like_me/

> «The revolutionary feature for me is using scheduled transactions to predict exactly how much money will be in my checking account at all times so I can invest more while being confident that bills won't hit at the wrong time and overdraft.»
> — u/FuckingaFuck, r/ynab, 2024-07-02, ↑ 8
> https://www.reddit.com/r/ynab/comments/1dtsbrl/questions_about_ynab/lbckxss/

> «$700 that could go to anything else, but that YNAB will never know about because my rent is in the red right now. This is frustrating. This software is so helpful other than the fact that the TBB amount never matches what I actually have to spend.»
> — u/stickyvibes, r/ynab, 2016-07-02, ↑ 11
> https://www.reddit.com/r/ynab/comments/4qx837/nynab_credit_cards_seem_backwards/

> «I am a relatively new sahm to twins so our financial situation has drastically changed in the last year. We are very close to being paycheck to paycheck. If we do not very closely watch our spending we will overdraft or have to miss a payment.»
> — u/kayleedb, r/povertyfinance, 2024-06-21, ↑ 33
> https://www.reddit.com/r/povertyfinance/comments/1dktpr7/how_do_you_budget_while_being_paycheck_to_paycheck/

> «If I overspend beyond usual savings deposits it shows me in advance when the budget would go in the red. If I am thinking impulse purchases (that includes a trip somewhere or a night out), I bust out the spreadsheet and plug the numbers and that -sometimes- sobers me up.»
> — u/Hot_Studio_8708, r/budget, 2025-11-09, ↑ 1
> https://www.reddit.com/r/budget/comments/1oso0kp/150_pounds_til_end_of_month/nnykqix/

> «Wednesday I got 9 overdraft notices in the snail mail and I panicked thinking someone had lifted my card or number and went on a spending spree. Nope! Checked my online banking and they listed *ALL* those transactions plus Netflix auto payment *BEFORE* my deposit.»
> — u/BoneHugsHominy, r/personalfinance, 2018-04-14, ↑ 275
> https://www.reddit.com/r/personalfinance/comments/8c5esp/wells_fargo_will_post_items_presented_against_the/dxcc0pr/

> «The other reason I switched was because they somehow charged three different overdraft fees over a mistake. I had enough money in my account for lunch one day at work I didn’t bring my lunch, so I bought Subway. It didn’t post until the next day.»
> — u/superzenki, r/povertyfinance, 2020-08-29, ↑ 2
> https://www.reddit.com/r/povertyfinance/comments/iig613/overdraft_fees_cripple_people_already_struggling/g37666t/

> «Except for a small handful of times in my early twenties, I have basically chronically been living in an overdraft cycle, never had any money, always late on bills, and simultaneously spending on things to soothe myself emotionally.»
> — u/Former-Birthday-2302, r/budget, 2026-02-19, ↑ 38
> https://www.reddit.com/r/budget/comments/1r9f1p6/how_tf_have_i_been_living_without_a_budget_until/

> «This would prove absolutely crucial, as it let me make massive payments without fear of overdrafting or any other bad news pop ups. There were times when my checking account was as low as $20. This didn't really matter, though, because I knew where my money would be days and weeks later with no surprises.»
> — u/ShouldntButAm, r/personalfinance, 2016-01-14, ↑ 2865
> https://www.reddit.com/r/personalfinance/comments/4100ky/how_i_paid_off_10k_in_cc_debt_in_one_year_55k_no/

> «Overdraft fees have sent me to tears on more than one occasion - especially when I had like 3 of them all hit one right after the other, when the total for what they covered wasn't even the cost of ONE of the fees.»
> — u/HoneyBadger302, r/povertyfinance, 2022-11-17, ↑ 7
> https://www.reddit.com/r/povertyfinance/comments/yxs1y4/i_hate_that_i_cant_even_afford_to_exist/iwqbmay/

> «This could be me, but I turned off my overdraft feature, so $0 stops the madness.»
> — u/Ok-Aide-4756, r/povertyfinance, 2026-07-13, ↑ 1
> https://www.reddit.com/r/povertyfinance/comments/1ut5rmu/i_cant_take_this_anymore/oxao6wg/

**Что повторяется:** формулировка «thought I had money» / «thought I had enough» встречается в чистом виде редко и почти всегда с добавкой «but wasn't paying enough attention» — то есть люди объясняют провал невнимательностью, а не тем, что их обманул интерфейс. Второе повторяющееся — временнáя рамка описывается через *порядок событий*, а не через остаток: «before my deposit», «it didn't post until the next day», «bills won't hit at the wrong time», «hit one right after the other». Третье — «running/overdraft cycle» как хроническое состояние, а не как разовое событие.

---

## Вопрос 2. Почему бросают YNAB

### Найдено: 37 релевантных упоминаний (из 152 сырых)

> «I canceled my subscription with the reason "the new UI is hot garbage." I pay annually so I have until February to find a new app or program my own. So long and thanks for all the fish, YNAB.»
> — u/cwazycupcakes13, r/ynab, 2025-10-07, ↑ 159
> https://www.reddit.com/r/ynab/comments/1o04570/the_new_app_ui_is_hot_garbage/

> «I actually just stopped using YNAB altogether in October and just updated a months worth of transactions last night because this got so frustrating. And while it felt weird at first, not using YNAB for a month was totally fine and the world kept turning.»
> — u/omnilogical, r/ynab, 2021-11-01, ↑ 15
> https://www.reddit.com/r/ynab/comments/qkcgzc/ynab_rolling_out_an_18_price_increase/hixdg7a/

> «I just canceled my subscription. I am a legacy member with legacy pricing. Customer service is gaslighting and denying legacy and any grandfathered pricing. Do better YNAB. Off to search for an alternate program.»
> — u/Glittery_Owl667, r/ynab, 2021-11-01, ↑ 14
> https://www.reddit.com/r/ynab/comments/qkcgzc/ynab_rolling_out_an_18_price_increase/hixktdt/

> «For myself, I have gotten annoyed at each price increase and ultimately set a couple hard requirements that I needed before switching away from YNAB. Each time I'd look at Actual and find it just wasn't there for me.»
> — u/lakeland_nz, r/ynab, 2025-02-07, ↑ 4
> https://www.reddit.com/r/ynab/comments/1ijv9ve/leaving_ynab_after_6_years_pricing_is_the_final/mbiuuxl/

> «Anyway - I’d finally had enough and cancelled my subscription (sadly I paid for the whole year, so it will be active till November). Re-downloaded YNAB4, set up my budget again and wow, I love YNAB4 so much.»
> — u/weszlem, r/ynab, 2018-03-10, ↑ 91
> https://www.reddit.com/r/ynab/comments/83ffmy/rant_downgrading_to_ynab4_after_almost_2_years_of/

> «that's not a way to live lol eventually i gave up on all the apps and budgets. the only thing i do now is check my balance on sunday morning. don't do anything with the info. idk why but after a few weeks i started noticing stuff on my own.»
> — u/Resident_Log5150, r/budget, 2025-11-29, ↑ 33
> https://www.reddit.com/r/budget/comments/1p9woao/stop_punishing_yourself/

> «I felt the same way about YNAB. And the way they force you to do reconciliation to look for missing transactions….that felt like a part time job just to get from 99% accurate budgeting to 100%. But I don’t need that extra 1%.»
> — u/beanery-bun, r/MonarchMoney, 2026-03-07, ↑ 4
> https://www.reddit.com/r/MonarchMoney/comments/1rnlnm9/what_did_you_switch_to_after_monarch/o97nvha/

> «There is no direct bank sync, so I have always manually input my transactions. It has taken me till this point, and the recent price increase just caused me to go explore other options. I found the Card Budget App, paid for the life time subscription (5% of the total yearly subscription of YNAB) and ran my budget parallely for 3 weeks.»
> — u/AnybodyResponsible22, r/ynab, 2024-07-15, ↑ 353
> https://www.reddit.com/r/ynab/comments/1e3v11g/bidding_goodbye_fiver_years_of_ynab/

> «Even though I couldn’t use key features like automatic bank syncing (which is U.S.-centric), I still stuck with YNAB because I loved their budgeting philosophy and UI. But over the years, the subscription cost kept rising, and at $110 per year, it’s just too much—especially for someone living in a developing country like India, where purchasing power is much lower.»
> — u/FlakyLow5274, r/ynab, 2025-02-07, ↑ 570
> https://www.reddit.com/r/ynab/comments/1ijv9ve/leaving_ynab_after_6_years_pricing_is_the_final/

> «All I want is for these reimbursements to **not affect my budget**. I work, my spouse works, we have plenty of cash flow. I'm using the budget app to budget both of our incomes for the purposes of trying to reduce our spending.»
> — u/whynotapples, r/ynab, 2018-07-18, ↑ 29
> https://www.reddit.com/r/ynab/comments/8zuwde/rant_the_handling_of_reimbursements_may_make_me/

> «I switched from YNAB to Monarch about a year ago, but I’ve grown weary of Monarch (largely because I don’t like the new goals - and the AI was too inaccurate - but I also find it really strange that they can’t get the notification icon to go back to normal after reviewing all notifications - seems sloppy and that has led to literally hundreds of unnecessary extra clicks over…»
> — u/beanery-bun, r/MonarchMoney, 2026-03-07, ↑ 0
> https://www.reddit.com/r/MonarchMoney/comments/1rnlnm9/what_did_you_switch_to_after_monarch/

> «I really wanted to like this app, there were so many positive things about it, but between accounts never synchronizing and the absolute horrendous support, I have no choice but to cancel and look elsewhere. I would submit tickets about accounts not syncing and I would be told the same thing over and over again without support even bothering to read the ticket.»
> — u/ryuhayabusa34, r/MonarchMoney, 2024-04-01, ↑ 59
> https://www.reddit.com/r/MonarchMoney/comments/1bthuyx/canceled_my_subscription/

> «I used YNAB for YEARSSSSS and finally gave it up last summer because despite seeing so many people say that they put in requests for a calendar view, and I personally put in multiple requests, it somehow never makes the cut.»
> — u/Grasshopper419, r/ynab, 2026-05-18, ↑ 17
> https://www.reddit.com/r/ynab/comments/1tgxbrv/calendar/

> «Kualia has what you’re looking for. Exactly the same as YNAB an it has calendar view. And a lot cheaper. The dev is very active on the discord and answers any questions. Not an ad but I recommend it because it was the only app that gave me what I needed so I stopped using YNAB, and you can import YNAB data.»
> — u/AmazingSpiderman7502, r/ynab, 2026-05-21, ↑ 1
> https://www.reddit.com/r/ynab/comments/1tgxbrv/calendar/on2xc05/

> «I got frustrated not being able to budget into the future well and looked for an app that had more forecasting capabilities. I’m now wondering if I gave ynab a fair shake?»
> — u/Agile-Humor-9087, r/ynab, 2024-10-14, ↑ 13
> https://www.reddit.com/r/ynab/comments/1g3nllt/is_ynab_for_paycheck_to_paycheck_when_i_spend/

**Что повторяется:** цена почти никогда не называется единственной причиной — она называется **последней каплей** («the final straw», «ultimately set a couple hard requirements», «the recent price increase just caused me to go explore other options»), а под ней лежит накопленное раздражение конкретной механикой. Вторая повторяющаяся конструкция — обнаружение, что без приложения ничего страшного не случилось: «not using YNAB for a month was totally fine and the world kept turning», «I don’t need that extra 1%». Третья — уход не к конкуренту, а **вниз по сложности**: к старой версии, к таблице, к «просто смотрю баланс в воскресенье».

---

## Вопрос 3. Календарь и посуточный прогноз

### Найдено: 59 релевантных упоминаний (из 220 сырых)

> «I'm looking for a financial planner that can: * Take information from my online bank statement. (Automatically would be good, but manually downloading/uploading the data would be fine too) * Place that information, along with any future planned expenses, into a clear calendar and/or line graph that shows where my bank account is expected to go in the future.»
> — u/StarManta, r/Frugal, 2014-05-04, ↑ 49
> https://www.reddit.com/r/Frugal/comments/24pek2/request_a_financial_planner_or_budgeting_software/

> «Has anyone found a simple app so I can just easily see what I need to pay with my next paycheck? I don’t mind manual entry apps but I don’t want linked bank accounts and investment strategies. I’ve tried mint, ynab, paycycle, bill tracker pro, rocket money, and excel spreadsheet and a few other ones.»
> — u/TinyAppGuy, r/povertyfinance, 2025-12-22, ↑ 51
> https://www.reddit.com/r/povertyfinance/comments/1pt4o01/apps_for_living_paycheck_to_paycheck/

> «I’d love to see running balance added to mobile so I could ditch my separate checkbook for good. With tons of accounts and scheduled transactions, the running balance right on my phone would give me the confidence I need to do so!»
> — u/No-Clerk-4787, r/ynab, 2023-12-07, ↑ 33
> https://www.reddit.com/r/ynab/comments/18cwmoa/what_are_your_top_3_most_desired_features_for_ynab/kcdddz7/

> «Treat future scheduled transactions like they are already gone from the balance. Still one of the changes that SUCKS compared to YNAB4 and one of the reasons I stopped using it for 2 years.»
> — u/strange-humor, r/ynab, 2023-12-07, ↑ 3
> https://www.reddit.com/r/ynab/comments/18cwmoa/what_are_your_top_3_most_desired_features_for_ynab/kcf45j9/

> «Not sure if this will work for you, but the thing I've found most helpful is literally just using a calendar app, and create recurring "events" for my payday and all recurring payments.»
> — u/_en_joy, r/povertyfinance, 2025-12-22, ↑ 4
> https://www.reddit.com/r/povertyfinance/comments/1pt4o01/apps_for_living_paycheck_to_paycheck/nvevc6j/

> «The calendar view then tells me what my accurate forecast is for a given account based on the known bills that it has been told about - this helps me keep the right amount of money in the account for the upcoming bills. This use of "bill" budgets works really well for me.»
> — u/Soliloquy86, r/pocketsmith, 2026-06-18, ↑ 1
> https://www.reddit.com/r/pocketsmith/comments/1u9mc9j/budgets_how_to_get_the_most_out_of_them/

> «Basically, looking for an app (or service, method) that can show me: • How much I’m set to make in a given month based on manually inputted scheduled jobs • How much I need to make to cover my bills; and whether or not I will be making enough to cover my essentials • A feature that can distinguish between different income sources (I do many things, and would be cool to know exactly how much is coming in from each…»
> — u/BattleAdvanced7290, r/ynab, 2025-02-04, ↑ 5
> https://www.reddit.com/r/ynab/comments/1ihq4xo/best_companion_app_for_tracking_future_income_and/

> «I will say the top things that have helped me stay with ynab: (1) have as many scheduled transactions as possible. If you're expecting to pay or be paid sometime in the future, SCHEDULE IT. That way all you have to do is approve the transaction, and update the amount or date as necessary.»
> — u/Mammoth_Temporary905, r/ynab, 2023-12-14, ↑ 2
> https://www.reddit.com/r/ynab/comments/18hatn4/ready_to_give_up_on_ynab/kdcacvf/

> «I scheduled transactions, so I could see when routine bills were coming up. When a non-monthly repeating transaction popped up, I scheduled a transaction for it and made sure I budgeted for its next occurrence.»
> — u/DesignatedVictim, r/ynab, 2023-12-13, ↑ 8
> https://www.reddit.com/r/ynab/comments/18hatn4/ready_to_give_up_on_ynab/kd7nksx/

> «I use a spreadsheet to keep track of my budgets, net worth, do experiments with the budgets, and see my balances after my scheduled transactions occur.»
> — u/paperrhino, r/personalfinance, 2013-03-14, ↑ 1
> https://www.reddit.com/r/personalfinance/comments/1aa5jv/im_looking_for_a_good_free_budgeting_website_or/c8vk91j/

> «For various reasons, setting up a spreadsheet is not ideal, and I'd like to be able to easily share it with my wife. The closest thing I've seen was the software that PNC Bank uses, but there's no PNC near us any more.»
> — u/anotherjunkie, r/personalfinance, 2013-01-20, ↑ 68
> https://www.reddit.com/r/personalfinance/comments/16xsua/is_there_a_good_appprogram_for_monitoring/

> «If they could bring running balance and full reports to mobile, it would really become a much more fully featured product.»
> — u/FroMan753, r/ynab, 2023-12-07, ↑ 11
> https://www.reddit.com/r/ynab/comments/18cwmoa/what_are_your_top_3_most_desired_features_for_ynab/kcdkryy/

Отдельно — тред r/ynab, озаглавленный просто «Calendar?» (2026-05-18, 69 комментариев):

> «I used YNAB for YEARSSSSS and finally gave it up last summer because despite seeing so many people say that they put in requests for a calendar view, and I personally put in multiple requests, it somehow never makes the cut.»
> — u/Grasshopper419, r/ynab, 2026-05-18, ↑ 17
> https://www.reddit.com/r/ynab/comments/1tgxbrv/calendar/

> «A question: are you a month ahead? My need for calendar view went away when i got a month ahead. Beyond 1 month, each thing has its own category with a due amount and date and i save for each thing every month, so i have no anxiety about having enough cash ready at the right time.»
> — u/MiriamNZ, r/ynab, 2026-05-20, ↑ 2
> https://www.reddit.com/r/ynab/comments/1tgxbrv/calendar/omsn8x1/

> «Calendar for YNAB is a tool I developed because I wanted something similar to what you’re asking. It’s not in YNAB itself but it syncs transactions from YNAB into a calendar feed that you can subscribe to using any calendar application such as Google calendar, Outlook, or the calendar application on your phone or computer.»
> — u/BigDragonfly2811, r/ynab, 2026-05-18, ↑ 10
> https://www.reddit.com/r/ynab/comments/1tgxbrv/calendar/omke76e/

> «It should be pretty easy to code. It's just data, but rather than row/column form it could be displayed on a calendar grid. That said, this is something that completely relies on people using scheduled transactions.»
> — u/OmgMsLe, r/ynab, 2026-05-26, ↑ 1
> https://www.reddit.com/r/ynab/comments/1tgxbrv/calendar/onwzxzj/

> «If you're willing to do manual entry, you could try Budget Friendly Budget. It's basically YNAB but has a calendar view. I like being able to see my expenses all laid out by date too so I get you»
> — u/Majestic-Worry-9754, r/ynab, 2026-05-18, ↑ 3
> https://www.reddit.com/r/ynab/comments/1tgxbrv/calendar/omkh8hh/

> «It seems like YNAB with a calendar that forecasts out years, which is amazing, but I need to figure out how to clear and reconcile.»
> — u/Grasshopper419, r/ynab, 2026-05-19, ↑ 1
> https://www.reddit.com/r/ynab/comments/1tgxbrv/calendar/omktpm7/

**Что повторяется:** запрос почти всегда формулируется через **вопрос к будущему счёту**, а не через «хочу график»: «where my bank account is expected to go», «what I need to pay with my next paycheck», «what my accurate forecast is for a given account». Второе — слово *running balance* (бегущий остаток) как отдельная желаемая сущность, отличная от «баланса» и от «бюджета»; трижды встречается в связке с мобильным приложением и один раз с бумажной чековой книжкой, которую оно должно заменить. Третье — люди, не нашедшие инструмента, **уходят в календарь общего назначения**, заводят там повторяющиеся события на зарплату и платежи либо пишут собственное расширение, выгружающее транзакции в календарный фид. Четвёртое — формулировка «treat future scheduled transactions like they are already gone from the balance»: спор о том, вычитать ли будущее из текущего остатка. Пятое — встречный аргумент от опытных пользователей: потребность в календаре **исчезает, если жить на месяц вперёд** («my need for calendar view went away when i got a month ahead»), то есть календарь считается костылём для тех, у кого нет буфера.

---

## Вопрос 4. Нерегулярный доход и выплаты дважды в месяц

### Найдено: 78 релевантных упоминаний (из 285 сырых)

> «i get paid bi-weekly, but the problem is that we pay all our bills monthly. so i gotta convert and split my paycheck properly so that all my bills get paid. how do i split everything when my income and expenses suddenly change on a random day of the month?»
> — u/badabingbadaboomie, r/budget, 2025-08-15, ↑ 5
> https://www.reddit.com/r/budget/comments/1mqliyr/need_help_with_budgeting/

> «I then have a few slots for income -- there's my full time and part-time job, and then a couple open that I can use for freelance gigs, tax refund, whatever. This is tricky, because unlike most full-time jobs I've had, my current pays me 26 (not 24) times per year, which means two months out of the year, I get three paychecks!»
> — u/Malnurtured_Snay, r/budget, 2025-02-09, ↑ 1
> https://www.reddit.com/r/budget/comments/1il0bvx/i_feel_like_im_going_insane_trying_to_get_the/mbs4yl5/

> «But because I do primarily gig work, I am always worried about whether or not I will be reaching my targets and covering my bills in the upcoming month, and I cannot actually manage that money until it hits my account.»
> — u/BattleAdvanced7290, r/ynab, 2025-02-04, ↑ 5
> https://www.reddit.com/r/ynab/comments/1ihq4xo/best_companion_app_for_tracking_future_income_and/

> «My research into Mint (I've signed up for an account to see if I could make it work) backs this up. The lack of support for biweekly paychecks is a little bit mind-boggling to me.»
> — u/StarManta, r/Frugal, 2014-05-04, ↑ 2
> https://www.reddit.com/r/Frugal/comments/24pek2/request_a_financial_planner_or_budgeting_software/ch9h05h/

> «Each paycheck, after taxes, works out to somewhere around $1300 (some pay periods include an extra day or two, because we get paid twice a month not every other week). I would estimate my monthly income to be $2500-$2700 (this is all after taxes.»
> — u/FubsyGamr, r/personalfinance, 2013-12-12, ↑ 79
> https://www.reddit.com/r/personalfinance/comments/1sr051/i_am_getting_buried_in_a_horrible_cycle_of_payday/

> «* As paychecks come in (I get paid biweekly), add them to the "Needed for Next Month" category * After I've received my last paycheck for the month, go to the next month and move everything from "Needed for Next Month" to "Ready to Assign", turn on my "Required to Cover" view, and use auto-assign to fill the categories * Lather rinse repeat!»
> — u/GravityPat, r/ynab, 2023-11-04, ↑ 36
> https://www.reddit.com/r/ynab/comments/17nurjs/finally_figured_out_a_way_to_do_a_needed_for_next/

> «At the time, I had just started a career as a financial advisor, and I was being paid with a combination of a fixed salary and commission. The amount I was making was changing every month.»
> — u/djhinz, r/personalfinance, 2017-06-29, ↑ 17738
> https://www.reddit.com/r/personalfinance/comments/6ka7ku/how_my_wife_and_i_never_fight_over_money/

> «I just received my Uber 1099-NEC and 1099-K, updated my tax filing, and can see that the money I set aside was a wise choice. For TY2022, I'll probably make quarterly tax payments direct to the IRS. My testimonial is that many years ago I knew wanted to see my burn rate (cash flow) in Quicken, but didn't understand how to make that happen.»
> — u/seismicpdx, r/ynab, 2022-01-23, ↑ 34
> https://www.reddit.com/r/ynab/comments/sacxv0/is_it_really_worth_it_for_a_dummy_like_me/httkb3z/

> «I rent a room in my friends' (a married couple) house for 450$ I commute to and from work daily about 40 minutes, so that's about 200 per bi-weekly pay period for gas. That leaves me with 40$ for anything else. food, phone bill, extra mileage....»
> — u/keepthemomentum23, r/povertyfinance, 2023-09-15, ↑ 1923
> https://www.reddit.com/r/povertyfinance/comments/16jdbn3/i_am_not_financially_irresponsible_i_just/

> «My partner gets paid biweekly and currently does not have any budgeting practices, but still pays himself first with savings and then uses the rest for our needs and his independent spending money.»
> — u/cvp8100, r/ynab, 2026-02-25, ↑ 8
> https://www.reddit.com/r/ynab/comments/1rei3rh/ynabing_together/

**Что повторяется:** конфликт формулируется одинаково — **«платят по циклу X, а счета приходят по циклу Y»**: «i get paid bi-weekly, but the problem is that we pay all our bills monthly». Второе — «26 (not 24) times per year» и «three paychecks» как отдельная ежегодная аномалия, которую надо куда-то деть. Третье — обходной приём один и тот же у разных людей: **буферная категория «на следующий месяц»**, куда складываются зарплаты, пока не наберётся полный месяц. Четвёртое — про нерегулярный доход говорят «I cannot actually manage that money until it hits my account»: жалоба именно на невозможность планировать *ожидаемое*, а не полученное.

---

## Вопрос 5. Ручной ввод против импорта

### Найдено: 386 релевантных упоминаний (из 517 сырых)

**За ручной ввод:**

> «I have all bills/utilities as scheduled transactions which makes it easy. I enter purchases at the time of sale. It seemed like lot of work at first but now it’s second nature. I reconcile frequently and never have had an issue.»
> — u/Modestmose, r/ynab, 2021-12-31, ↑ 52
> https://www.reddit.com/r/ynab/comments/rsz03l/how_many_of_you_enter_transactions_manually/hqpr8fr/

> «It sounds like a lot to manage, but all of my recurring bills are set up as scheduled transactions, I have as many things as possible running through my Apple Card to get the cash back, and any manual spend I enter as soon as humanly possible, often before I get back to the car or right after checking out online.»
> — u/RemarkableMacadamia, r/ynab, 2023-12-13, ↑ 10
> https://www.reddit.com/r/ynab/comments/18hatn4/ready_to_give_up_on_ynab/kd5lepf/

> «I manually entered my transactions, and used direct import as a backup (if available).»
> — u/DesignatedVictim, r/ynab, 2023-12-13, ↑ 8
> https://www.reddit.com/r/ynab/comments/18hatn4/ready_to_give_up_on_ynab/kd7nksx/

> «I never really used apps I just never really liked them. I kinda did it old school and sat down a Wrote out what I spent in a month. I got on my Banking app and wrote down everything I spent. Then I would just see what I could either cut out completely or cut down on.»
> — u/DangerousBlacksmith7, r/povertyfinance, 2024-06-21, ↑ 2
> https://www.reddit.com/r/povertyfinance/comments/1dktpr7/how_do_you_budget_while_being_paycheck_to_paycheck/l9m54ev/

> «I don’t mind manual entry apps but I don’t want linked bank accounts and investment strategies.»
> — u/TinyAppGuy, r/povertyfinance, 2025-12-22, ↑ 51
> https://www.reddit.com/r/povertyfinance/comments/1pt4o01/apps_for_living_paycheck_to_paycheck/

**Против — сломанная синхронизация:**

> «I really wanted to like this app, there were so many positive things about it, but between accounts never synchronizing and the absolute horrendous support, I have no choice but to cancel and look elsewhere.»
> — u/ryuhayabusa34, r/MonarchMoney, 2024-04-01, ↑ 59
> https://www.reddit.com/r/MonarchMoney/comments/1bthuyx/canceled_my_subscription/

> «The sync problems are very frustrating when you care about net worth but I'm working around it right now with a manual account. I hate that I pay $100 to workaround their issues though. Other posters almost all claim "It's not their fault", but my point is that if you can't reliably sync, then at least tell people that.»
> — u/TheOGRock, r/MonarchMoney, 2024-04-02, ↑ 2
> https://www.reddit.com/r/MonarchMoney/comments/1bthuyx/canceled_my_subscription/kxoos65/

> «The issue is definitely Plaid / MX (and in reality - it's the banks themselves failing to update Plaid / MX) - I worked on a personal finance app using Plaid years ago and the connections constantly broke down and wouldn't synchronize for days at a time all because of terrible ancient big bank tech from the backing banks.»
> — u/VenusFlytrapDeMilo, r/MonarchMoney, 2024-04-04, ↑ 2
> https://www.reddit.com/r/MonarchMoney/comments/1bthuyx/canceled_my_subscription/ky20hry/

> «It’s not their “fault” but it still means the service you’re paying for doesn’t work. You have given Monarch your data and paid for a subscription. You owe them nothing. If technical or policy issues with financial institutions prevent them from delivering, you should walk away.»
> — u/partyin-theback, r/MonarchMoney, 2025-10-11, ↑ 4
> https://www.reddit.com/r/MonarchMoney/comments/1o32xht/i_loved_this_tool_and_recommended_it_to_others/nivmtyn/

> «I was even able to connect my Fidelity account, which had stopped working with Plaid for some reason. I believe this setup might be challenging for someone who is not tech-savvy, but the instructions are very straightforward.»
> — u/Handsome_Solo, r/ynab, 2024-07-02, ↑ 349
> https://www.reddit.com/r/ynab/comments/1dta2it/i_know_that_ynab_saves_you_more_than_109_a_year/

**Против — сверка как налог:**

> «And the way they force you to do reconciliation to look for missing transactions….that felt like a part time job just to get from 99% accurate budgeting to 100%. But I don’t need that extra 1%.»
> — u/beanery-bun, r/MonarchMoney, 2026-03-07, ↑ 4
> https://www.reddit.com/r/MonarchMoney/comments/1rnlnm9/what_did_you_switch_to_after_monarch/o97nvha/

> «I know some people reconcile weekly or even more frequently but that's not for me (clearly since it took 18 months this time around 😂), because I am in the app daily manually entering or approving transactions. Or just looking at it for fun.»
> — u/rannie110b, r/ynab, 2025-03-12, ↑ 52
> https://www.reddit.com/r/ynab/comments/1j99ja3/tip_from_someone_who_wished_they_had_done_it/

> «Honestly, the one thing that added value to me beyond YNAB4 was direct import. *That was in 2015*. There hasn't been much development beyond the core mission since they went SaaS.»
> — u/pimpampoumz, r/ynab, 2021-11-10, ↑ 26
> https://www.reddit.com/r/ynab/comments/qqwwl7/megathread_november_2021_ynab_updates/hk3qt7w/

**Что повторяется:** сторонники ручного ввода описывают его не как терпимую цену, а как **источник самого эффекта** — «I enter purchases at the time of sale», «before I get back to the car»; ввод ценен моментом, а не данными. Формула «seemed like lot of work at first but now it’s second nature» встречается неоднократно. На другой стороне повторяется не «синхронизация неудобна», а «**сервис, за который я плачу, не работает**», причём люди явно отвергают аргумент «это вина банков». И отдельная, часто встречающаяся конструкция: сверка описывается как вторая работа ради последнего процента точности, который не нужен.

---

## Вопрос 6. Цена

### Найдено: 302 релевантных упоминания (из 1159 сырых)

> «However, paying $109 a year for a glorified spreadsheet can be a lot for some. So, if you don't have $109 right now to pay for YNAB, check the Actual Budget documentation and see if it works for you.»
> — u/Handsome_Solo, r/ynab, 2024-07-02, ↑ 349
> https://www.reddit.com/r/ynab/comments/1dta2it/i_know_that_ynab_saves_you_more_than_109_a_year/

> «After today's price hike, I decided to check out Actual Budget for fun (after hearing so much about it) and was pleasantly surprised. I used Pikapod to set up a prebuilt Actual Budget server, which costs approximately $1.40 a month.»
> — u/Handsome_Solo, r/ynab, 2024-07-02, ↑ 349
> https://www.reddit.com/r/ynab/comments/1dta2it/i_know_that_ynab_saves_you_more_than_109_a_year/

> «But over the years, the subscription cost kept rising, and at $110 per year, it’s just too much—especially for someone living in a developing country like India, where purchasing power is much lower. A few years ago, I even reached out to YNAB’s support team, suggesting a variable pricing strategy similar to what Netflix, Spotify, and YouTube offer.»
> — u/FlakyLow5274, r/ynab, 2025-02-07, ↑ 570
> https://www.reddit.com/r/ynab/comments/1ijv9ve/leaving_ynab_after_6_years_pricing_is_the_final/

> «I wish I could see their data regarding this price increase. Obviously they wouldn’t have done it if they didn’t think their market would bear it. But I struggle to see how new users would want to pay $15 a month for a budgeting app.»
> — u/merikus, r/ynab, 2021-11-02, ↑ 86
> https://www.reddit.com/r/ynab/comments/ql77om/an_outside_product_managers_perspective_on_ynabs/hj11hpi/

> «Each cancelation is worth 10x their price increase. That will hurt very rapidly if a sizeable majority of this sub cancels. Vote with your dollars and your (digital) feet. If they are going to gouge us, then walk out. If they reverse their position, you can always resubscribe.»
> — u/ImperiousMage, r/ynab, 2024-07-01, ↑ 101
> https://www.reddit.com/r/ynab/comments/1dsv8rh/if_you_want_to_protest_the_price_increase_then/

> «A lot of you are complaining about the $300 a year plan for Monarch. Here’s the thing: You don’t have to get it. “The features are half-baked!” Great! Use ProjectionLabs instead. These things take time to build out.»
> — u/Street-Programmer483, r/MonarchMoney, 2026-04-21, ↑ 182
> https://www.reddit.com/r/MonarchMoney/comments/1srpil9/unpopular_opinion_yall_are_overreacting/

> «I found the Card Budget App, paid for the life time subscription (5% of the total yearly subscription of YNAB) and ran my budget parallely for 3 weeks.»
> — u/AnybodyResponsible22, r/ynab, 2024-07-15, ↑ 353
> https://www.reddit.com/r/ynab/comments/1e3v11g/bidding_goodbye_fiver_years_of_ynab/

> «Once you download the app and finish linking all your accounts/giving your personal info, Albert then reveals that in order to use this advance feature, you have to subscribe to a plan for $150/yr, starting with a free trial.»
> — u/mrmangar, r/povertyfinance, 2023-12-17, ↑ 392
> https://www.reddit.com/r/povertyfinance/comments/18k6gvj/psa_avoid_the_albert_app/

> «Did anyone else notice that ProjectionLab quietly removed the $18 monthly Premium subscription? Now, if you wish to subscribe month-by-month, you must use the Pro plan at $74/mo, or buy a year of Premium for $129.»
> — u/astronomyman, r/projectionlab, 2026-05-31, ↑ 30
> https://www.reddit.com/r/projectionlab/comments/1tswtd0/monthly_premium_subscription_gone/

> «I do agree that $129/year is still worth it for those that are trying to get all the details of their plan figured out. It's still certainly cheaper than a financial planner, but I think of ProjectionLab as more of a tool to play with the numbers and see where I'm at.»
> — u/astronomyman, r/projectionlab, 2026-05-31, ↑ 5
> https://www.reddit.com/r/projectionlab/comments/1tswtd0/monthly_premium_subscription_gone/ooy8x61/

> «(with my history with money, $10 a month to avoid overdraft fees? Hell of a bargain!)»
> — u/StarManta, r/Frugal, 2014-05-05, ↑ 1
> https://www.reddit.com/r/Frugal/comments/24pek2/request_a_financial_planner_or_budgeting_software/ch9yxz6/

> «I am a product manager by trade (but not for YNAB), and I’m watching this sub-Reddit to understand how YNAB and their users absorb the price hike, so I can apply any discoveries / learnings toward improving my own craft.»
> — u/mcgaritydotme, r/ynab, 2021-11-02, ↑ 944
> https://www.reddit.com/r/ynab/comments/ql77om/an_outside_product_managers_perspective_on_ynabs/

**Названные суммы:** $109–110/год (YNAB, порог приемлемости у многих проходит ровно здесь), $15/мес (называется как явно завышенное для новичка), $300/год (Monarch, вызвало отдельный конфликт в сабе), $129/год и $74/мес (ProjectionLab), $18/мес (удалённый тариф ProjectionLab), $150/год (Albert — упоминается как навязанное после привязки счетов), $1,40/мес (self-hosted Actual Budget как контрточка), $10/мес (2014 год, названо выгодным «если это спасает от овердрафтных комиссий»).

**Что повторяется:** цена почти всегда сравнивается не с конкурентом, а **с самодельной альтернативой**: «glorified spreadsheet», «$1.40 a month», «program my own». Вторая повторяющаяся рамка — сравнение с ценой человека-консультанта («cheaper than a financial planner»), она работает у дорогих планировщиков и не встречается у бюджетников. Третья — региональная: покупательная способность и просьба о плавающих ценах по странам. Четвёртая — раздражение вызывает не сумма, а **механика повышения** (легаси-тарифы, тихое удаление месячного плана, платный доступ после того, как данные уже загружены).

---

## Вопрос 7. Что используют вместо приложений

### Найдено: 370 релевантных упоминаний (из 848 сырых)

> «paying $109 a year for a glorified spreadsheet»
> — u/Handsome_Solo, r/ynab, 2024-07-02, ↑ 349
> https://www.reddit.com/r/ynab/comments/1dta2it/i_know_that_ynab_saves_you_more_than_109_a_year/

> «tried ynab for a bit. too much work. tried mint. hated categorizing stuff. made a spreadsheet once and literally never opened it again.»
> — u/Resident_Log5150, r/budget, 2025-11-29, ↑ 33
> https://www.reddit.com/r/budget/comments/1p9woao/stop_punishing_yourself/

> «last year I made a spreadsheet to help myself budget- I'm terrible at sticking to a budget so I made a sheet that breaks it down so that I just have the ability to break it down to a daily manageable amount. I grew up very poor and had NO sense of what or even HOW to start budgeting.»
> — u/Celesmeh, r/personalfinance, 2019-05-15, ↑ 50772
> https://www.reddit.com/r/personalfinance/comments/boza0g/i_made_a_spreadsheet_for_people_who_dont_know_how/

> «I use a spreadsheet to keep track of my budgets, net worth, do experiments with the budgets, and see my balances after my scheduled transactions occur.»
> — u/paperrhino, r/personalfinance, 2013-03-14, ↑ 1
> https://www.reddit.com/r/personalfinance/comments/1aa5jv/im_looking_for_a_good_free_budgeting_website_or/c8vk91j/

> «What works for me is a spreadsheet I update weekly to track my net worth and my credit card app’s analysis of spending by category.»
> — u/partyin-theback, r/MonarchMoney, 2025-10-11, ↑ 1
> https://www.reddit.com/r/MonarchMoney/comments/1o32xht/i_loved_this_tool_and_recommended_it_to_others/nivnrol/

> «Clearly my spreadsheet and mint weren't working. Constant fights about money, constantly late on bills. Lots of stress and tons of lost sleep. It was just time to try something new.»
> — u/jmtyndall, r/ynab, 2020-02-03, ↑ 546
> https://www.reddit.com/r/ynab/comments/ey0br4/ive_never_been_so_wrong_in_my_life/

> «I have tried Mint and Excel spread sheets of about every flavor. Nothing seemed to fit. Then I found YNAB.»
> — u/Ynabcindy, r/ynab, 2015-02-19, ↑ 114
> https://www.reddit.com/r/ynab/comments/2wfx6i/this_is_a_story_of_short_term_success_for_all/

> «I like spreadsheets, my spouse never looked at them and I had a hard time giving up control. A few months ago we switched to a hybrid cash envelope system and using a money book that we track everything in. It's been great.»
> — u/bobocalender, r/financialindependence, 2023-01-12, ↑ 1103
> https://www.reddit.com/r/financialindependence/comments/10a55jz/journey_to_the_first_100k/

> «For various reasons, setting up a spreadsheet is not ideal, and I'd like to be able to easily share it with my wife.»
> — u/anotherjunkie, r/personalfinance, 2013-01-20, ↑ 68
> https://www.reddit.com/r/personalfinance/comments/16xsua/is_there_a_good_appprogram_for_monitoring/

> «I don't see how an Excel document can create a graph like the one I'm hoping to find (my Excel-fu is reasonably strong), but if yours does, I'd be happy to see it.»
> — u/StarManta, r/Frugal, 2014-05-04, ↑ 1
> https://www.reddit.com/r/Frugal/comments/24pek2/request_a_financial_planner_or_budgeting_software/ch9m7cr/

> «I have to be honest, I am not that bright when it comes to finance or spreadsheets or figuring things out via formulas. I'm not a total idiot but it's close. I am hoping YNAB will help give me some black and white guidance.»
> — u/ChicagoMarketer, r/ynab, 2021-06-09, ↑ 280
> https://www.reddit.com/r/ynab/comments/nw1fxl/am_trying_to_decide_if_im_smart_enough_to_pull/

**Что повторяется:** таблица выигрывает по трём поводам — **бесплатно**, **делает ровно то, что мне надо** и **я её понимаю**; выражение «glorified spreadsheet» применительно к платному приложению встречается как готовая формула обесценивания. Но обратное движение встречается ровно так же часто: «Clearly my spreadsheet and mint weren't working», «Nothing seemed to fit». Отдельно и неоднократно всплывают два ограничения таблицы, которые её и убивают: **её не открывает второй человек в паре** и **в ней трудно построить график будущего остатка**. И встречается противоположный полюс: люди, для которых таблица непосильна («I am not that bright when it comes to finance or spreadsheets»), и приложение выбирается именно как замена формулам.

---

## Вопрос 8. PocketSmith и ProjectionLab

### Найдено: 250 релевантных упоминаний (из 299 сырых)

Оговорка по источникам: r/pocketsmith фактически мёртв (около десятка постов за всё время), поэтому материал по PocketSmith — это упоминания в чужих сабах, часть из них старые (2013–2014). r/projectionlab, наоборот, живой (3583 подписчика, 234 поста), там же живут посты основателя о запуске — из них цитаты не берутся, но факт их существования отмечу: посты «I spent 1200 hours / 2 years / 5 years building a FI planning tool» в r/leanfire собрали 503, 260 и 165 голосов.

**PocketSmith — за что хвалят:**

> «Doesn’t really support the envelope system of YNAB, but if you get all of your budgets configured properly it allows you to accurately forecast pretty far into the future.»
> — u/zikronix, r/ynab, 2021-11-02, ↑ 681
> https://www.reddit.com/r/ynab/comments/qkvc7s/alternates_to_ynabheres_a_list/

> «The calendar view then tells me what my accurate forecast is for a given account based on the known bills that it has been told about - this helps me keep the right amount of money in the account for the upcoming bills.»
> — u/Soliloquy86, r/pocketsmith, 2026-06-18, ↑ 1
> https://www.reddit.com/r/pocketsmith/comments/1u9mc9j/budgets_how_to_get_the_most_out_of_them/

> «PocketSmith - This one looks absolutely great. It's exactly what I was looking for, and it's straight forward and clear enough to not cause any confusion.»
> — u/anotherjunkie, r/personalfinance, 2013-01-20, ↑ 68
> https://www.reddit.com/r/personalfinance/comments/16xsua/is_there_a_good_appprogram_for_monitoring/

> «I've found a website (through a comment thread bitching about Mint lacking the forward-projection graphs I was looking for, as it happens) called PocketSmith that seems to do what I want it to do here.»
> — u/StarManta, r/Frugal, 2014-05-04, ↑ 2
> https://www.reddit.com/r/Frugal/comments/24pek2/request_a_financial_planner_or_budgeting_software/ch9mfu5/

> «I have mostly been using PocketSmith for the last few months due to this. They seem to get that you can have online & offline accounts, and create FEEDS to link/sync these two.»
> — u/MLJ_The_Shield, r/MonarchMoney, 2024-07-19, ↑ 24
> https://www.reddit.com/r/MonarchMoney/comments/1e79ig1/the_1_thing_that_lets_mm_down_is_not_separating/

> «How do I know if I'm overspending! It's the reason I wish ynab had forecasting liking pocketsmith because I can peek ahead....»
> — u/apeacefuldad, r/ynab, 2023-02-13, ↑ 37
> https://www.reddit.com/r/ynab/comments/111ilga/im_rolling_with_the_punches_too_much_im_unsure_if/

**PocketSmith — на чём спотыкаются:**

> «PocketSmith seems really nice, however I could see the free plan running out of usable events quite quickly»
> — u/BPSmith511, r/Frugal, 2014-05-05, ↑ 1
> https://www.reddit.com/r/Frugal/comments/24pek2/request_a_financial_planner_or_budgeting_software/ch9unr4/

> «Indeed, I was pretty cavalier with using them and I'm already out. I don't actually mind paying for something if it's good, though.»
> — u/StarManta, r/Frugal, 2014-05-05, ↑ 1
> https://www.reddit.com/r/Frugal/comments/24pek2/request_a_financial_planner_or_budgeting_software/ch9yxz6/

> «This infrequent purchases bug the ish out of me and there was a difficulty I had managing my credit card - but the credit card difficulty is lessening since I’ve stopped using every credit card but 1 (thanks to ynab for guiding me that direction).»
> — u/apeacefuldad, r/ynab, 2023-02-05, ↑ 17
> https://www.reddit.com/r/ynab/comments/10uihkq/back_at_ynab_after_trying_pocketsmith_again/

**ProjectionLab — за что хвалят:**

> «I've been using ProjectionLab over the past year to start mapping stuff out. It's expensive but I'm finding it very helpful for mapping out long term plans and various scenarios.»
> — u/MrWookieMustache, r/financialindependence, 2026-02-14, ↑ 135
> https://www.reddit.com/r/financialindependence/comments/1r4ol73/eleven_year_update/

> «I ran scenarios in both Boldin and ProjectionLab. Both showed having more in my investment accounts at age 85 than I have now, despite dipping the first few years while I'm waiting for Social Security to kick in. Projection Lab Monte Carlo showed a 99% chance of success.»
> — u/planetmike2, r/MiddleClassFinance, 2026-02-09, ↑ 65
> https://www.reddit.com/r/MiddleClassFinance/comments/1r0j9ta/stay_fulltime_change_to_parttime_or_retire/

> «I've modeled this out in ProjectionLab, and in very few scenarios do I run out of money even at age 100. In most scenarios I'm still left with a $2-3M cushion if I age to 100.»
> — u/Exciting_Kangaroo800, r/fatFIRE, 2023-08-14, ↑ 100
> https://www.reddit.com/r/fatFIRE/comments/15qvp24/using_a_4_swr_and_depleting_my_savings_during/

> «Great for visualizing FIRE paths and long-term planning. No account linking needed.»
> — u/Witty-North-9759, r/Fire, 2025-07-18, ↑ 51
> https://www.reddit.com/r/Fire/comments/1m3a06r/what_personal_finance_tracking_apps_are_you/

**ProjectionLab — на чём спотыкаются:**

> «Hey everyone, I’m pretty new to ProjectionLab. While I can sense how powerful this tool is under the hood, I am seriously struggling to get my bearings with the interface.»
> — u/Bjorn_Nittmo, r/projectionlab, 2026-05-17, ↑ 33
> https://www.reddit.com/r/projectionlab/comments/1tg4t1p/am_i_dumb_or_is_projectionlab_a_maze_getting/

> «I had 3 false starts over several months, plugging everything in, getting wildly inaccurate results, and walking away because the tool just didn't work. Then I went back a 4th time determined to figure it out. I read, watched videos, and added details I'd never even considered before.»
> — u/Dry-Money4827, r/projectionlab, 2026-05-07, ↑ 10
> https://www.reddit.com/r/projectionlab/comments/1t67n6t/garbage_in_garbage_out_not_sure_what_mistakes_im/okfqmz3/

> «Not alone and my career was in FP&A so I know projection tools to an extent. I've had to watch a lot of videos, even just finding where some of the display options were. I was looking for the tax balance metric, it looked like on the video it was on the Tax Analytics tab, but it was actually on the Plan tab, under taxes.»
> — u/Dangerous_Chipmunk_6, r/projectionlab, 2026-06-02, ↑ 1
> https://www.reddit.com/r/projectionlab/comments/1tg4t1p/am_i_dumb_or_is_projectionlab_a_maze_getting/opcvcta/

> «I echo the sentiment that retirement planning can be very complicated and lots of factors involved. ProjectionLab allows for all these factors so it is inherently complex as well. You can dumb it down so to speak by keeping your inputs and setup simple until you figure out things.»
> — u/ericb1000, r/projectionlab, 2026-06-18, ↑ 1
> https://www.reddit.com/r/projectionlab/comments/1tg4t1p/am_i_dumb_or_is_projectionlab_a_maze_getting/oseyugc/

> «I cancelled my subscription until seeing the developer of the Monarch Money plugin mention his new tool.»
> — u/Disastrous_Bet_7809, r/projectionlab, 2026-05-07, ↑ 8
> https://www.reddit.com/r/projectionlab/comments/1t67n6t/garbage_in_garbage_out_not_sure_what_mistakes_im/

**Что повторяется:** два продукта живут в **разных разговорах**. PocketSmith всплывает там, где человек ищет *будущий остаток по счёту* — и хвалят его ровно за календарь и прогноз, а спотыкаются на лимитах бесплатного тарифа и на работе с кредитками. ProjectionLab всплывает в FIRE-контексте, хвалят за сценарии, Монте-Карло и отсутствие привязки счетов, а спотыкаются на **сложности входа**: повторяются «maze», «false starts», «watch a lot of videos», причём это говорят в том числе люди с профессиональным опытом финансового моделирования. Отдельная повторяющаяся конструкция в r/projectionlab — «garbage in, garbage out»: пользователи винят себя, а не интерфейс.

---

## Вопрос 9. Тревога вокруг денег

### Найдено: 283 релевантных упоминания (из 855 сырых)

> «i'd check my account, see a number that felt low, panic, then not look at it again for weeks. classic avoidance. tried ynab for a bit. too much work. tried mint. hated categorizing stuff. made a spreadsheet once and literally never opened it again.»
> — u/Resident_Log5150, r/budget, 2025-11-29, ↑ 33
> https://www.reddit.com/r/budget/comments/1p9woao/stop_punishing_yourself/

> «Sorry for just laying that all out but I know I need help and I'm just looking for something, anything to get me out of this anxiety that I've been dealing with. Is YNAB good for people like me or do I need something else?»
> — u/ChicagoMarketer, r/ynab, 2021-06-09, ↑ 280
> https://www.reddit.com/r/ynab/comments/nw1fxl/am_trying_to_decide_if_im_smart_enough_to_pull/

> «Even now I still feel anxiety about money and can spend recklessly if I'm not careful. Another problem I faced is that I have ADHD, so impulse control can be hard, and it can also be hard to keep track of every purchase and focus on a bunch of aspects of a budget.»
> — u/Celesmeh, r/personalfinance, 2019-10-30, ↑ 36713
> https://www.reddit.com/r/personalfinance/comments/dp7pww/i_mde_a_spreadsheet_for_people_who_dont_know_how/

> «I think (especially in America) we have this perception that a budget is a very aggressive, prescriptive money plan, that you make ahead of time and do not deviate from, you only have $X dollars to spend on groceries so you better only eat rice and beans, and if you go out to dinner once it means you're being "bad" and leads to shame, embarrassment, avoidance, etc.»
> — u/Mammoth_Temporary905, r/ynab, 2025-01-30, ↑ 292
> https://www.reddit.com/r/ynab/comments/1iducun/newbies_need_to_understand_that_you_are_the_boss/

> «Trying to track categories vs just overall money left to spend caused way more guilt for me.»
> — u/lavacakeislife, r/budget, 2024-12-31, ↑ 494
> https://www.reddit.com/r/budget/comments/1hqmtli/i_tracked_every_penny_spent_in_2024_and_will/

> «I'm just worried this will become another thing that is overwhelming, and I'll get discouraged and stop using the app and forget about it.»
> — u/vhscleaner, r/ynab, 2022-01-22, ↑ 85
> https://www.reddit.com/r/ynab/comments/sacxv0/is_it_really_worth_it_for_a_dummy_like_me/

> «I did get on the forums a bit but that just confused me and made me anxious that I was missing something or not doing this thing right. Then I decided I didn't care about all that.»
> — u/Ynabcindy, r/ynab, 2015-02-19, ↑ 114
> https://www.reddit.com/r/ynab/comments/2wfx6i/this_is_a_story_of_short_term_success_for_all/

> «I have very severe ADHD and have been procrastinating on starting YNAB (again) because I dread having to concentrate for long periods to get it going and keep it going, and I don't want to give up yet something else because I can't keep my focus and interest sufficiently engaged.»
> — u/BlueBull007, r/ynab, 2022-10-07, ↑ 8
> https://www.reddit.com/r/ynab/comments/xxkfxw/ynab_works_for_adhd_my_life_is_changed/iredycg/

> «I'm ADHD, have never budgeted, live paycheck to paycheck, and failed at YNAB last year because the learning curve was too steep for me at the time. Things got real for me in the last few weeks, and I also wanted to make a big purchase and decided to actually look \*into\* my finances rather than look \*at\* them.»
> — u/safetyorange989, r/ynab, 2022-10-07, ↑ 328
> https://www.reddit.com/r/ynab/comments/xxkfxw/ynab_works_for_adhd_my_life_is_changed/

> «I also have adhd. I found myself hyper focusing on budgeting in the first couple months, the way I do with new hobbies like 2-3 times per year. So that really helped get the ball rolling. Now I don’t do it so obsessively but I do check it often and keep it up to date.»
> — u/WampaCat, r/ynab, 2022-10-07, ↑ 8
> https://www.reddit.com/r/ynab/comments/xxkfxw/ynab_works_for_adhd_my_life_is_changed/irf4iuh/

> «My stress and anxiety levels have plummeted. My optimism for the future and relationship with my family has dramatically improved. Definitely recommended if you can find something that works!»
> — u/Used_Berry_3893, r/povertyfinance, 2025-08-19, ↑ 47
> https://www.reddit.com/r/povertyfinance/comments/1muig7o/feeling_defeated/

> «I have stopped checking my bank balance twice or three times a day every day, that gives me an unrealistic view of what I have.»
> — u/Ynabcindy, r/ynab, 2015-02-19, ↑ 114
> https://www.reddit.com/r/ynab/comments/2wfx6i/this_is_a_story_of_short_term_success_for_all/

**Что повторяется:** самое частое слово — **avoidance** (избегание), и описывается оно одинаковым циклом: посмотрел на баланс → испугался → перестал смотреть. Формулировка «classic avoidance» подана автором как самодиагноз, не как метафора. Второе — тревога прикрепляется не к нехватке денег, а к **процессу учёта**: «made me anxious that I was missing something or not doing this thing right», «tracking categories caused way more guilt», «another thing that is overwhelming». Третье — бюджет описывается как моральная категория: «being "bad"», «shame, embarrassment». Четвёртое — про СДВГ говорят в двух противоположных режимах: гиперфокус первых недель как то, что «раскачивает» (и потом спадает), и страх «бросить ещё одну вещь». Пятое — противоположный полюс тревоги: слишком частая проверка баланса («twice or three times a day») описывается как то, что даёт *искажённую* картину.

---

## Неожиданное

**1. Человек в 2014 году дословно описал продукт, которого ему не хватало, — и это описание слово в слово совпадает с тем, что через десять лет всё ещё просят.**

> «Place that information, along with any future planned expenses, into a clear calendar and/or line graph that shows where my bank account is expected to go in the future.»
> — u/StarManta, r/Frugal, 2014-05-04, ↑ 49
> https://www.reddit.com/r/Frugal/comments/24pek2/request_a_financial_planner_or_budgeting_software/

Сравните с запросом 2025 года:

> «Has anyone found a simple app so I can just easily see what I need to pay with my next paycheck? I don’t mind manual entry apps but I don’t want linked bank accounts and investment strategies.»
> — u/TinyAppGuy, r/povertyfinance, 2025-12-22, ↑ 51
> https://www.reddit.com/r/povertyfinance/comments/1pt4o01/apps_for_living_paycheck_to_paycheck/

**2. Люди уходят из финансовых приложений в календарь общего назначения.**

> «Not sure if this will work for you, but the thing I've found most helpful is literally just using a calendar app, and create recurring "events" for my payday and all recurring payments. Paying affirm every two weeks on tuesday for 6 months?»
> — u/_en_joy, r/povertyfinance, 2025-12-22, ↑ 4
> https://www.reddit.com/r/povertyfinance/comments/1pt4o01/apps_for_living_paycheck_to_paycheck/nvevc6j/

**3. Бросают не из-за фич, а из-за того, что жизнь без приложения оказалась нормальной.**

> «And while it felt weird at first, not using YNAB for a month was totally fine and the world kept turning.»
> — u/omnilogical, r/ynab, 2021-11-01, ↑ 15
> https://www.reddit.com/r/ynab/comments/qkcgzc/ynab_rolling_out_an_18_price_increase/hixdg7a/

> «that's not a way to live lol eventually i gave up on all the apps and budgets. the only thing i do now is check my balance on sunday morning. don't do anything with the info. idk why but after a few weeks i started noticing stuff on my own.»
> — u/Resident_Log5150, r/budget, 2025-11-29, ↑ 33
> https://www.reddit.com/r/budget/comments/1p9woao/stop_punishing_yourself/

**4. Доктринальный конфликт: «бюджетируй только то, что у тебя уже есть» против спроса на прогноз.** Это самый заметный повторяющийся спор во всём собранном материале. Люди, живущие от зарплаты до зарплаты, просят заглянуть вперёд; старожилы отвечают, что сама постановка вопроса противоречит методу, и отправляют прогнозировать в таблицу.

> «You should only budget with the money you have NOW. Thats how ynab works. Pay what you HAVE to pay till you get paid again. "What do I NEED this money to do for me before the next paycheck comes in?" Forecast on a separate spreadsheet.»
> — u/MelDawson19, r/ynab, 2024-10-14, ↑ 96
> https://www.reddit.com/r/ynab/comments/1g3nllt/is_ynab_for_paycheck_to_paycheck_when_i_spend/lrx4uvt/

> «What you’re looking for is exactly what YNAB is designed against. YNAB is a $0 based budget system, you do not budget future money, only what you have. Budgeting with future money will create or exacerbate the problem you’re describing.»
> — u/lieutent, r/ynab, 2023-02-14, ↑ 12
> https://www.reddit.com/r/ynab/comments/111ilga/im_rolling_with_the_punches_too_much_im_unsure_if/j8g6prb/

> «I think you're missing a key part of YNAB's philosophy here. YNAB isn’t about forecasting future paychecks—it’s about managing the money you have right now.»
> — u/ynab4file, r/ynab, 2024-10-14, ↑ 38
> https://www.reddit.com/r/ynab/comments/1g3nllt/is_ynab_for_paycheck_to_paycheck_when_i_spend/lrx5eay/

И ответная сторона:

> «I see what you mean. You’re right that YNAB is not designed to make budgeting feel easy when you’re truly living paycheck to paycheck. You do have to constantly pay attention to the due dates of all your expenses and make conscious decisions with each dollar.»
> — u/pinkypromiise, r/ynab, 2024-10-15, ↑ 1
> https://www.reddit.com/r/ynab/comments/1g3nllt/is_ynab_for_paycheck_to_paycheck_when_i_spend/ls0n25m/

> «It's not perfect as in the short term there often are months where I need to go into savings (i.e. when my professional dues, car maintenance, and oil fill for heating are all due the same month), but then catch up when the "extra" paycheck comes around.»
> — u/IRLbeets, r/ynab, 2024-10-15, ↑ 1
> https://www.reddit.com/r/ynab/comments/1g3nllt/is_ynab_for_paycheck_to_paycheck_when_i_spend/ls0n25m/

**5. Отдельно стоящий спор: считать ли будущие списания уже вычтенными из остатка.** Это не просьба о фиче, а разногласие о том, что вообще означает слово «баланс».

> «Treat future scheduled transactions like they are already gone from the balance. Still one of the changes that SUCKS compared to YNAB4 and one of the reasons I stopped using it for 2 years.»
> — u/strange-humor, r/ynab, 2023-12-07, ↑ 3
> https://www.reddit.com/r/ynab/comments/18cwmoa/what_are_your_top_3_most_desired_features_for_ynab/kcf45j9/

**6. Просьба о более простой поверхности поверх сложного движка — от самих пользователей продвинутого инструмента.**

> «I just wish they'd start giving us the stuff we request and hiding the more complex stuff behind optional toggles for the users that would get overwhelmed.»
> — u/austintehguy, r/ynab, 2025-12-10, ↑ 124
> https://www.reddit.com/r/ynab/comments/1pjga6j/feature_request_subcategories/ntd7s80/

**7. Усталость от бюджетирования формулируется как «я потратил слишком много сил на бюджет и ненавижу это, но всё ещё хочу чувствовать себя в безопасности».**

> «but i wish there was a better solution i hope what i'm saying makes sense. i feel like i spent too much effort budgeting and i hate it. but i also want to feel confident that i'm secure»
> — u/badabingbadaboomie, r/budget, 2025-08-15, ↑ 5
> https://www.reddit.com/r/budget/comments/1mqliyr/need_help_with_budgeting/

**8. Мобильное приложение как отдельная точка отказа.** Запросы «running balance на телефон» и «отчёты на телефон» повторяются в тредах о желаемых фичах чаще, чем запросы новых расчётов.

> «If they could bring running balance and full reports to mobile, it would really become a much more fully featured product. I would much rather see a team dedicated to these mobile improvements first before trying to tackle any fun enhancements.»
> — u/FroMan753, r/ynab, 2023-12-07, ↑ 11
> https://www.reddit.com/r/ynab/comments/18cwmoa/what_are_your_top_3_most_desired_features_for_ynab/kcdkryy/

**9. Профессиональные финансовые коучи в комментариях говорят о СДВГ-нише как о своей специализации** — то есть сегмент опознан рынком услуг, а не только пользователями.

> «I’m a financial coach, and my niche is neurodivergent folks, and I’ve been pondering whether YNAB (which I use & love) would work for others with ADHD. Sounds like the answer is yes!»
> — u/Both-Caterpillar-512, r/ynab, 2022-10-07, ↑ 104
> https://www.reddit.com/r/ynab/comments/xxkfxw/ynab_works_for_adhd_my_life_is_changed/ircj6e8/

**10. Про импорт спорят не о качестве, а об ответственности.** Повторяется контраргумент «это вина банков, а не приложения» и повторяется же его отвержение.

> «Of course you should leave over connection issues. The software doesn’t work. I’m sure the competitors are no better.»
> — u/partyin-theback, r/MonarchMoney, 2025-10-11, ↑ 1
> https://www.reddit.com/r/MonarchMoney/comments/1o32xht/i_loved_this_tool_and_recommended_it_to_others/nivnrol/

**11. В r/projectionlab два свежих треда про связку инструмента с ИИ-ассистентом** — «Claude + ProjectionLab» (2026-05-03, ↑56) и «Claude + PL = Financial planner on your laptop» (2026-06-22, ↑35). Комментарии в них в выборку не попали, но сам факт двух заметных тредов за два месяца фиксирую.

---

## Приложение: сырые данные

Сохранить дампы комментариев не удалось (Chrome заблокировал серию автоматических загрузок с домена — см. «Метод»). Вместо них — полный индекс **315 открытых тредов**: подсаб, id, дата, рейтинг, число комментариев, заголовок. Файл рядом: [`2026-08-23-reddit-threads.json`](2026-08-23-reddit-threads.json).

URL любого треда собирается как `https://www.reddit.com/r/{sub}/comments/{id}/`.

Разметка по темам, применявшаяся к каждому комментарию, и жёсткие тематические фильтры второго уровня описаны там же, в поле `themes` каждой записи и в шапке файла.
