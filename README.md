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

If you have your own `.golangci.json`|`.golangci.toml`|`.golangci.yaml`|`.golangci.yml` config file, the plugin will detect and use it.  
Otherwise, select linters you need:

![](explanation/settled.png)

Click `OK`, you are all set!

Please keep in mind that **golangci-lint** is a linter tool, **it only works if your project could build (no syntax error)**, or it will report no issue.

### Project Root Setting
* If your Go project is the root project, the default setting will work perfectly.
* If you are using Intellij Ultimate, you have a Go project which isn't the root project, but nested in directories, select `Project Root` to the Go project path.
* If you have multiple Go sub-projects in the root project, uncheck `Project Root`. Be aware that only the config file directly under the root project will be used.

### Go Project As Sub Folder
**Skip this if you are not using config file (eg: `.golangci.yml`)**  
If you are using Intellij, the Go project is not the root project (sub-folder nested in the root project),  
and the Go project has its own golangci-lint config file,  
Please select `project root` to the path of Go project, in order to let the plugin correctly pick up the config file.


### For Windows users
Please disable goimports / gofmt / gci linters. Use [File-Watcher](https://tech.flyclops.com/posts/2016-06-14-goimports-intellij.html) in IDEA.  
*It you insist using those 3 linters, download <a href="http://ftp.gnu.org/gnu/diffutils/">GNU diff</a> & <a href="https://ftp.gnu.org/pub/gnu/libiconv/">GNU LibIconv</a> and put them in system PATH (eg: C:\WINDOWS). Normally it's missing from the system.*

## Report a bug
* Please note down your platform (Win/Linux/Mac), IDEA/Goland version, Go version
* If the plugin reports an error, please copy-paste the error content

## What's next
* Performance improvement
* Code quality improvement
