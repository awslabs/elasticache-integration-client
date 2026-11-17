# Contributing Guidelines

Thank you for your interest in contributing to the Integration Client for ElastiCache. Whether it's a bug report, a new feature, a correction, or additional documentation, we greatly value feedback and contributions from our community.

Please read through this document before submitting any issues or pull requests, to make sure we have all the necessary information to effectively respond to your bug report or contribution.

## Reporting bugs, feature requests and other issues

We welcome you to use the GitHub issue tracker to report bugs or suggest features.

When filing an issue, please check existing open, or recently closed, issues to make sure somebody else hasn't already reported it. Please try to include as much information as you can, such as:

- The client and its version, and the AWS SDK version.
- A reproducible test case or series of steps.
- What you expected to happen and what happened, including any error with its cause.
- Anything unusual about your environment or deployment.

## Contributing via pull requests

Contributions via pull requests are much appreciated. Before sending us a pull request, please ensure that:

1. You are working against the latest source on the `main` branch.
2. You check existing open, and recently merged, pull requests to make sure someone else hasn't addressed the problem already.
3. You open an issue to discuss any significant work: we would hate for your time to be wasted.

To send us a pull request, please:

1. Fork the repository.
2. Modify the source. Please focus on the specific change you are contributing; reformatting all the code makes it hard for us to focus on your change.
3. Ensure the client's local checks pass. Each client's `DEVELOPER.md` lists them.
4. Commit to your fork using clear commit messages.
5. Send us a pull request, answering any default questions in the pull request interface.
6. Pay attention to any automated CI failures reported in the pull request, and stay involved in the conversation.

GitHub provides additional documentation on [forking a repository](https://help.github.com/articles/fork-a-repo/) and [creating a pull request](https://help.github.com/articles/creating-a-pull-request/).

## Development

```text
.
├── node/    # Node.js client
├── java/    # Java client
├── python/  # Python client
└── shared/  # Language-neutral client contracts
```

- Building, testing and releasing a client: [Python](python/DEVELOPER.md), [Node.js](node/DEVELOPER.md), [Java](java/DEVELOPER.md).
- Adding third-party code to the repository or to a published package? Add its attribution to [THIRD-PARTY-LICENSES](THIRD-PARTY-LICENSES).

## Finding contributions to work on

Looking at the existing issues is a great way to find something to contribute on. Issues labeled `help wanted` are a great place to start.

## Code of Conduct

This project has adopted the [Amazon Open Source Code of Conduct](https://aws.github.io/code-of-conduct). For more information see the [Code of Conduct FAQ](https://aws.github.io/code-of-conduct-faq) or contact opensource-codeofconduct@amazon.com with any additional questions or comments.

## Security issue notifications

If you discover a potential security issue in this project, we ask that you notify AWS/Amazon Security via our [vulnerability reporting page](http://aws.amazon.com/security/vulnerability-reporting/). Please do **not** create a public GitHub issue.

## Licensing

See the [LICENSE](LICENSE) file for our project's licensing. We will ask you to confirm the licensing of your contribution.
