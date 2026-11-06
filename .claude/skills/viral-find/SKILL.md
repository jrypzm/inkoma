---
name: viral-find
description: Find the faceless reels that are beating their own account's normal in any niche, and work out why they won. Use when someone wants viral reels to study, reference reels, outlier reels, what's working in a niche, faceless accounts to learn from, or reel ideas, or when they share Instagram account links or a screenshot of the Explore grid and ask what to make. First step of the viral pack (viral-find, viral-script, viral-animate, viral-voice, viral-check).
---

# viral-find

Big view counts alone mislead. An account with 11 million followers gets millions on an average day, so those reels teach little. What teaches is the outlier: a reel that did 50x what the same account normally does, with the same followers and the same algorithm. Something about that video worked. This skill finds those.

Scripts in this guide live next to this file, so use their full path.

## 1. Get the reels

Read `~/.claude/viral/profile.md` if it exists, for the niche and the accounts. Ask for 3 to 5 accounts if you have none. Create the project folder `viral/<short-name>/` in the working directory now, and run everything from inside it. Then use the first route that's available:

**A. An Apify tool is connected.** Run `apify/instagram-reel-scraper` with `{"username": [...], "resultsLimit": 30}` and a spending cap (`maxTotalChargeUsd` of 0.5). Save the dataset items to `reels.json` with only the fields below, because full output is huge: `ownerUsername, url, caption, videoPlayCount, videoViewCount, likesCount, commentsCount, videoDuration, timestamp, isPinned, videoUrl`.

**B. `APIFY_TOKEN` is set.**
```bash
python3 fetch_reels.py account1 account2 account3 --out reels.json
```
Both Apify routes cost money per reel (about $0.0026 per reel on Apify's free plan when this was written), so say roughly what it'll cost before running a big list.

**C. Free: a screenshot.** Ask the user to screenshot an account's Reels tab or the Explore grid. Read the view counts from the image into a CSV with the columns `account,views,url,caption,date`. It's rough but it works. Use one account per screenshot when you can, because comparing a reel with its own account is the whole point.

Only have a niche and no accounts? Ask for one account they already like, run `apify/instagram-profile-scraper` on it and read `relatedProfiles`. Without Apify, have them open Instagram's suggested accounts and screenshot those.

## 2. Find the outliers

```bash
python3 outliers.py reels.json --top 15
```

It merges duplicates (the same video often gets posted twice), compares each reel with the typical reel from the same account, and writes `outliers.json`. Reels under a week old and pinned reels are left out of the "typical" number, because young reels are still climbing and pinned ones are hand-picked winners.

Read the flags. `[under 2 days old]` means the reel is unfinished. `[multiple inflated]` means most of that account's reels are too new to compare against, so don't quote that number as if it were solid. If a small account shows no outliers, look at its `best x` column and lower `--min-views`. `--today` sets the date when the data is older.

## 3. Study the top five

The numbers say which, not why. To see the videos, run this soon after fetching, because Instagram's links expire. It makes two contact sheets per reel, then deletes the video:

```bash
python3 peek.py outliers.json --top 5
```

Open each `peek/*_hook.jpg` and `_whole.jpg`, then work out:

- **Hook:** the first picture, the words on screen, and which shape it uses (see `../viral-script/hooks.json`). A caption is often a moral or a sales line, not what the reel opens with, so don't treat it as the hook.
- **Skeleton:** what happens in order, in a few words. For example: everyday problem, twist, what it says about you, line to send a friend.
- **Why it spread:** the feeling that made people share it. Being seen, being told off, love for someone, laughing at yourself.
- **Length and pace.**
- **Could objects tell it?** This pack draws objects, places and type, not people. Many stories translate well. Say so if one doesn't.

If you can't get the videos, as on the screenshot route, work from what you have and say in `brief.md` that you judged from captions and numbers only.

## 4. Stay honest about what you found

- **Don't say how a video was made unless the account says so.** An account's bio or caption can tell you it uses a tool. Its style can't. Write "says it uses Claude Code" or "tool unknown".
- **Say the size of the luck.** Even accounts with huge hits have many quiet reels. Show the typical number next to the best one, so nobody reads a single hit as what they should expect.
- **Numbers are dated.** Note the date you pulled them.

## 5. Hand over

Write `brief.md` in the project folder: the account table, the top outliers with their links, your read on each, and three video ideas in the user's niche that borrow a skeleton without copying the words. If they have no niche yet, base the ideas on everyday life, which travels furthest, and ask what they'd like the page to be about. Show the table and the three ideas. Then point to `/viral-script` for the one they pick.
