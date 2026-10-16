# Stocks

Stocks is an app for the APH Monarch that draws a stock's price history as a tactile graph, so blind and low-vision investors can read a chart by touch. Built at the Center for Assistive Technology Training Northwest (CATT-NW) at the Washington State School for the Blind.

## Why it matters

Price charts are how most people follow the market, and they are visual by default. A screen reader can read out today's price, but it cannot show the shape of a month of price movement: whether it climbed steadily, dropped suddenly, or went sideways. Stocks puts that shape on the Monarch's tactile display and speaks the price, change and time, so a blind investor gets the same kind of picture a sighted investor gets. It is also a hands-on way to teach graph reading and data literacy.

## What it does

- Opens to a home list with Search and three starting tickers: AAPL, MSFT and GOOGL.
- Search takes any ticker symbol you type.
- Draws the price history across the whole tactile display. The price, change, time and whether the market is open are spoken instead of drawn, so the graph gets all of the display.
- Shows five ranges: 1 day, 1 week, 1 month, 1 year and All. D-pad Up moves forward through the ranges and D-pad Down moves backward. Both wrap around.
- Double-tap a point on the graph to hear its price and date.
- Has a Details page with Open, Previous Close, Day's Range, Volume and 52-Week Range. Panning Down opens it and the Back button returns.

## What you need

Hardware:
- An APH Monarch with developer access, connected to the internet
- A USB cable from the Monarch to your computer

Software:
- Android Studio
- Access to the Monarch SDK through APH. You need an APH deploy token to build this app. It is not included here, and you must have one before you start.
- A free Twelve Data account and API key. Step 3 below shows how to get one.

## Setup

1. **Important: USB debugging must be turned on on the Monarch, or the app cannot be installed or run.** Turn on developer options, then USB debugging.
2. In the project's root folder, copy `keystore.properties.example` to a new file named `keystore.properties`. Replace `YOUR_TOKEN_HERE` with your APH deploy token. You need to already have this token. Never commit this file to GitHub.
3. Get a Twelve Data API key and add it to the app:
   1. Go to twelvedata.com/register and create a free account with your email or Google sign-in. The free Basic plan does not need a credit card.
   2. Sign in and open twelvedata.com/account/api-keys. Select Reveal on the Secret key row, then copy the key.
   3. In the project's root folder, copy `apikeys.properties.example` to a new file named `apikeys.properties`.
   4. Open `apikeys.properties` in a text editor. It has one line, `twelve_data_api_key=YOUR_API_KEY_HERE`. Replace `YOUR_API_KEY_HERE` with your key. Do not add quotes or spaces, and put nothing after the key. Save the file.
   5. Never commit or share `apikeys.properties`. It is already ignored by Git, which keeps it out of GitHub.
   The key is built into the app when you build it. If you add or change the key, build and install the app again.
4. Connect the Monarch to your computer by USB and accept the "Allow USB debugging?" prompt on the Monarch.
5. From the project's root folder, build and install the app:
   - Windows: `gradlew.bat installDebug`
   - Mac or Linux: `./gradlew installDebug`
   You can also use the Run button in Android Studio. If Gradle can't find Java, point `JAVA_HOME` to the JDK that comes with Android Studio.
6. Open Stocks on the Monarch.

## Using the app

- On the home list, tap a ticker to open its chart.
- To look up another stock, choose Search, type a ticker symbol such as NVDA, and confirm.
- On the chart, press D-pad Up or Down to change the range, and double-tap a point to hear its price and date.
- Press Panning Down on the chart to open Details. Press Back to return from Details or from the chart.

## How it works

The app gets prices from the Twelve Data API using your key. The 1 month, 1 year and All ranges use daily closing prices. The 1 day range uses 5-minute prices and the 1 week range uses hourly prices.

The free Twelve Data plan allows 8 API credits per minute and 800 per day, and each request for a symbol uses at least one credit. To stay under those limits, the app reuses recent results instead of asking again: 10 minutes for daily prices, 1 minute for intraday prices and 30 seconds for quotes.

Prices come from Twelve Data and may be delayed depending on your plan. Stocks is not financial advice.

## Troubleshooting

- If the app says "No API key configured", it was built without a key. Check that `apikeys.properties` is in the project's root folder and that its line starts with `twelve_data_api_key=`. Then build and install the app again.
- If Twelve Data reports an invalid API key, copy the key again from your Twelve Data account. Make sure there are no quotes, spaces or extra characters in `apikeys.properties`.
- If Twelve Data reports that you reached the rate limit, wait one minute and try again. The free plan allows 8 credits per minute and 800 per day. The daily limit resets at midnight UTC.
- If the app says "Network error", check that the Monarch is connected to the internet.
- If a symbol is not found, check the ticker and try again. The app shows Twelve Data's own message.
- If Gradle fails to download the Monarch SDK with a 401 error, check your deploy token. If the SDK has already been downloaded once, you can build with `gradlew.bat installDebug --offline`.
- If Android Studio doesn't see the Monarch, check that USB debugging is still on. It can turn off after a firmware update. Unplug, plug back in, and accept the prompt again.

## Known issues

- The Monarch's Zoom keys are reserved by its screen reader, so the app uses the D-pad to change the range.
- On the chart screen, the screen reader blocks single taps. Only double-taps reach the app.
- Panning Down does not always open Details. The Back button reliably returns from it.

## Credits

Built by Abdul-Rahman Mohammed during his internship at CATT-NW at the Washington State School for the Blind, for the APH Monarch. Thank you to Dr. Yue-Ting Siu, CATT-NW Coordinator, and Dr. Joshua Miele for their guidance and feedback.

## License

MIT License. See the LICENSE file.

## Contact

CATT-NW: cattnw@wssb.wa.gov
