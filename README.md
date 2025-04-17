Crypto Price Tracker - Interview Assessment
Project Overview
This app tracks real-time cryptocurrency prices from Binance and Coinbase using WebSockets, built with Kotlin, Compose, Coroutines, Flow, and MVVM concepts.

Features Implemented
Two Tabs: "Favorites" and "All Coins".

Favorites Tab:

Pre-populated with 10 popular coins (BTC, ETH, SOL, etc.).

Displays live prices from both Binance and Coinbase side-by-side.

Shows the percentage price spread between the two sources.

Allows adding/removing favorites using the Star icon.

A Floating Action Button (FAB) appears if presets are removed, allowing quick re-addition.

All Coins Tab:

Displays a list of all USDT trading pairs fetched from the Binance REST API (shows name and symbol).

Includes a search bar to filter this list.

Allows adding coins to the Favorites list via the star icon.

Trade-offs & Development Notes
Given the time constraints for this assessment, several trade-offs were made:

WebSocket Strategy (Favorites Only):

An initial approach involved dynamically subscribing/unsubscribing via WebSocket to all coins visible on the screen (using pagination and REST data). However, this implementation encountered issues and wasn't working reliably.

To deliver a functional app demonstrating the core requirements (real-time updates, dual sources), I made the sacrifice to simplify this. The current version only uses WebSockets for the Favorites list. Live updates appear for favorited coins, while the "All Coins" list shows static information from the REST API (unless an item is also a favorite). This is a pragmatic solution chosen due to time limits.

Coinbase Error Handling (e.g., BNB):

When a coin favorited on Binance isn't available on the Coinbase feed (like BNB-USD), the Coinbase connection might enter a loop of errors or reconnection attempts.

While some logic was added to limit reconnects and suppress persistent UI errors for this case, fully resolving this infinite loop/error state gracefully requires more time than was available for the assessment.

Simplified Architecture:

As this is a test app focused on specific features, strict clean architecture rules (like separating data models from state, using domain layers, etc.) were not fully implemented to save time. You might notice some files mix responsibilities (e.g., data classes holding state) – this was a conscious decision to prioritize delivering the requested functionality within the assessment's scope.

These points represent areas for improvement if this were a production application but demonstrate the core technical skills required by the assessment within the expected timeframe.
