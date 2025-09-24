<div align="center">
  <!-- You can add a project logo here if you have one -->
  <!-- <img src="path/to/your/logo.png" alt="Films Logo" width="150"/> -->
  <h1>Films - Taopiaopiao Cinema Scraper</h1>
  <p>
    An Android application that automates the process of scraping all applicable cinemas for a Taopiaopiao movie coupon across every city and exports the data to an Excel file.
  </p>
  
  <!-- Badges -->
  <a href="#"><img alt="Platform" src="https://img.shields.io/badge/platform-Android-brightgreen.svg"/></a>
  <a href="#"><img alt="Language" src="https://img.shields.io/badge/language-Java-blue.svg"/></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue"/></a>
</div>

[**English**](#) | [**中文**](README_zh.md)

---

## 💡 Why Films?

For ticketing professionals or data analysts, obtaining a comprehensive list of cinemas that accept a specific **Taopiaopiao** (淘票票) coupon is a critical task. However, the official platform provides no bulk export or cross-city search functionality. The conventional methods require tedious, PC-based manual work or complex scraper scripts that can easily be blocked.

**Films** was built to solve this challenge. It's a fully automated, "fire-and-forget" Android tool. Simply input a coupon's `fcode`, and the app will:
1.  Automatically discover all available cities.
2.  Iterate through each city to scrape the list of applicable cinemas.
3.  Compile the nationwide data.
4.  Export the final, clean list into a single `.xlsx` Excel file.

This transforms a multi-hour manual task into a simple, one-click background process on your phone.

## ✨ Features

*   **📲 Fully Automated**: One-click operation. The app handles city discovery, data scraping, and result aggregation automatically.
*   **🌏 Nationwide Coverage**: Scrapes data from every city listed on the platform, providing a complete dataset.
*   **📄 Excel Export**: Directly generates a well-formatted `.xlsx` file containing columns for City, Cinema Name, and Address.
*   **⚙️ WebView-Powered**: Utilizes an invisible WebView to render the page and execute JavaScript, closely mimicking real user behavior to reduce the risk of being blocked.
*   **📊 Real-time Progress**: A clear UI shows the current status, progress bar, and logs, keeping you informed throughout the process.
*   **⚠️ Robust Error Handling**: Includes timeouts and retry logic for failed cities, ensuring the process completes even with network instability.

## 🛠️ Technical Deep Dive: How It Works

The application operates by automating a hidden WebView instance, guided by a state machine (`IDLE`, `FETCHING_CITIES`, `FETCHING_CINEMAS`).

1.  **Phase 1: City List Discovery**
    *   Loads the coupon page for a sample city (e.g., Shanghai).
    *   Injects JavaScript to programmatically simulate a tap on the "City Selector" button.
    *   Waits for the city list to populate in the DOM, then extracts the name and code for every city in the country.
    *   The results are passed from JavaScript back to the native Java code via a `JavascriptInterface`.

2.  **Phase 2: Nationwide Cinema Scraping**
    *   The app then begins a loop through the newly acquired city list.
    *   For each city, it constructs the specific URL and loads the page.
    *   Once the page finishes loading, another JavaScript payload is injected to find and extract the name and address of all listed cinemas.
    *   If no cinemas are found or the page is empty, it gracefully moves to the next city.

3.  **Phase 3: Data Compilation & Export**
    *   All scraped cinema data is stored in memory.
    *   Upon completion, the app uses the **Apache POI** library to generate an Excel workbook.
    *   It then uses Android's Storage Access Framework to let the user choose a location and save the final `.xlsx` file.

## 🚀 Getting Started (For Developers)

To get a local copy up and running, follow these simple steps.

### Prerequisites

*   Android Studio (Arctic Fox or newer)
*   An Android device or emulator (API 28+)

### Installation

1.  Clone the repo:
    ```sh
    git clone https://github.com/treelang-dev/films.git
    ```
2.  Open the project in Android Studio.
3.  Let Gradle sync the required dependencies, including `org.apache.poi`.
4.  Build and run the application. The app requires the `INTERNET` permission to function.

## 🧑‍💻 How to Use the App

1.  Obtain the `fcode` for the Taopiaopiao coupon you wish to analyze.
2.  Launch the **Films** app.
3.  Enter the `fcode` into the text field.
4.  Tap the **"Start Scraping"** button.
5.  Wait for the process to complete. You can monitor the progress on the screen.
6.  Once finished, the app will prompt you to save the generated `all_city_cinemas.xlsx` file.

## ⚠️ Disclaimer

This project is intended for educational and research purposes only. It is a proof-of-concept demonstrating web scraping techniques on Android. The reliability of this tool depends on the internal structure and APIs of the Taopiaopiao website, which are subject to change without notice. The developers of this project are not responsible for any misuse of this tool. Please use it responsibly and respect the terms of service of the target website.

## 🤝 Contributing

Contributions are what make the open-source community an amazing place. Any contributions you make are **greatly appreciated**. Please fork the repo and create a pull request.

## 📄 License

Distributed under the Apache License 2.0. See `LICENSE` file for more information.
