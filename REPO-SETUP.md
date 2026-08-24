# GitHub Repository Setup

After extracting this folder:

```bash
git init
git add .
git commit -m "Initial WorldMemory alpha.53.1 reconstructed source"
git branch -M main
git remote add origin <YOUR_REPOSITORY_GIT_URL>
git push -u origin main
```

Then enable the GitHub Wiki in repository settings and upload the separate WorldMemory Wiki export.

## Before making the repository public

Read `SOURCE-RECOVERY.md`. The repository is a buildable reconstruction, but some older engine classes are preserved as compiled bytecode because their original Java source was not available in the recovered project history.

No open-source license has been selected automatically. Add the license you want before describing the repository as open source.
