[![Publish](https://github.com/xxpxxxxp/intellij-plugin-golangci-lint/workflows/Publish/badge.svg)](https://github.com/xxpxxxxp/intellij-plugin-golangci-lint/actions)
[![Issues](https://img.shields.io/github/issues/xxpxxxxp/intellij-plugin-golangci-lint)](https://github.com/xxpxxxxp/intellij-plugin-golangci-lint/issues)
[![License](https://img.shields.io/github/license/xxpxxxxp/intellij-plugin-golangci-lint)](https://github.com/xxpxxxxp/intellij-plugin-golangci-lint/blob/master/LICENSE)
[![Version](https://img.shields.io/jetbrains/plugin/v/12496-go-linter)](https://plugins.jetbrains.com/plugin/12496-go-linter)
[![#Download](https://img.shields.io/jetbrains/plugin/d/12496-go-linter.svg)](https://plugins.jetbrains.com/plugin/12496-go-linter)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/50dd3264c0f74e85929d53bd780fcdfd)](https://app.codacy.com/manual/xxpxxxxp/intellij-plugin-golangci-lint?utm_source=github.com&utm_medium=referral&utm_content=xxpxxxxp/intellij-plugin-golangci-lint&utm_campaign=Badge_Grade_Dashboard)

# Intellij/Goland Linter Inspection Plugin

Write code, write better code

All credit goes to [GolangCI-Lint authors](https://github.com/golangci/golangci-lint/graphs/contributors).

A lot of code pieces copied from [clion-clangtidy](https://bitbucket.org/baldur/clion-clangtidy/src/default/), [kotlin](https://github.com/JetBrains/kotlin), and [Goland plugin](https://plugins.jetbrains.com/plugin/9568-go) itself.

## How to use
After the plugin installed, you can see a popup on IDEA startup, or go to settings directly:

![](explanation/init.png)

A **golangci-lint** executable is needed. Choose one from combobox if you already have it in your PATH, or `Open...` select one from disk, or `Get Latest` download one from Github release.  
Then select linters you need:

![](explanation/settled.png)

If you have your own golangci-lint config file, in most case the plugin will detect and use it. If it didn't, choose `I'm using custom config file` checkbox.  
Click `OK`, you are all set!

Please keep in mind that **golangci-lint** is a linter tool, **it only works if your project could build**, or it will report no issue.

### For Windows users
Please disable goimports / gofmt linters. Use [File-Watcher](https://tech.flyclops.com/posts/2016-06-14-goimports-intellij.html) in IDEA.  
*It you insist using those 2 linters, download <a href="http://ftp.gnu.org/gnu/diffutils/">GNU diff</a> & <a href="https://ftp.gnu.org/pub/gnu/libiconv/">GNU LibIconv</a> and put them in system PATH (eg: C:\WINDOWS). Normally it's missing from the system.*

## Report a bug
* Please note down your platform (Win/Linux/Mac), IDEA/Goland version, Go version
* If the plugin reports an error, please copy-paste the error content

## What's next
* Bug fix
* Code quality improvement
