/*
 * Root build for DiziPal Cloudstream plugin.
 * Gradle wrapper jar bu depoda yoksa:
 *   gradle wrapper
 * komutuyla wrapper oluşturun.
 */
plugins {
    // Versiyonlar burada tutulur; module'de apply edilecek
    id("com.android.library") version "8.1.0" apply false
    kotlin("android") version "1.9.22" apply false
    // Cloudstream Gradle plugin versiyonu pluginManagement reposundan çözümlenir
}
