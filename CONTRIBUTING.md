# Contributing to CouponTracker

Thank you for your interest in contributing to CouponTracker! We welcome contributions from the community.

## 🤝 How to Contribute

### Before You Start

1. **Review the AI Guardrails**
   - Read [Coupon Extraction Rules](docs/ai_guardrails/COUPON_EXTRACTION_RULES.md)
   - Read [AI Editing Checklist](docs/ai_guardrails/AI_EDITING_CHECKLIST.md)
   - All contributors (human or AI) must acknowledge these policies

2. **Check Existing Work**
   - Search [existing issues](https://github.com/chetank2/coupontracker/issues)
   - Check [open pull requests](https://github.com/chetank2/coupontracker/pulls)
   - Review the [project roadmap](docs/IMPLEMENTATION_STATUS.md)

3. **Understand the Architecture**
   - Read the [Technical Architecture Guide](TECHNICAL_ARCHITECTURE_GUIDE.md)
   - Review the [LLM Integration docs](docs/LLM_INTEGRATION.md)

## 🚀 Development Setup

### Prerequisites

- **Android Development**: Android Studio, SDK 24+, NDK
- **Python Environment**: Python 3.8+
- **Build Tools**: Gradle 8.x, CMake 3.22+
- **Optional**: CUDA-capable GPU for ML training

### Setup Steps

1. **Clone the Repository**
   ```bash
   git clone https://github.com/chetank2/coupontracker.git
   cd coupontracker
   ```

2. **Android App Setup**
   ```bash
   # Open in Android Studio
   # Sync Gradle dependencies
   ./gradlew build
   ```

3. **Python Training Setup**
   ```bash
   cd coupon-training
   python3 -m venv venv
   source venv/bin/activate  # On Windows: venv\Scripts\activate
   pip install -r requirements.txt
   ```

4. **Run Tests**
   ```bash
   # Android unit tests
   ./gradlew test
   
   # Android instrumentation tests
   ./gradlew connectedAndroidTest
   
   # Python tests
   pytest tests/
   ```

## 📝 Pull Request Process

### 1. Create a Feature Branch

```bash
git checkout -b feature/your-feature-name
# or
git checkout -b fix/your-bug-fix
```

### 2. Make Your Changes

- Follow the existing code style
- Write clear, descriptive commit messages
- Add tests for new functionality
- Update documentation as needed

### 3. Run Quality Checks

```bash
# Run AI invariant validator
python scripts/check_ai_invariants.py

# Run all tests
./gradlew test
pytest tests/

# Run fuzz tests
pytest tests/fuzz -q

# Run golden corpus tests
pytest tests/golden -q
```

### 4. Submit Pull Request

1. Push your branch to your fork
2. Open a Pull Request against `main` branch
3. Fill out the PR template completely
4. Ensure all CI checks pass

### Pull Request Checklist

- [ ] Ran `python scripts/check_ai_invariants.py` successfully
- [ ] All unit tests pass locally
- [ ] All integration tests pass locally
- [ ] Code follows project style guidelines
- [ ] Documentation updated (if applicable)
- [ ] Acknowledged AI Guardrails in PR description
- [ ] Added tests for new functionality
- [ ] No breaking changes (or clearly documented)

## 🐛 Reporting Bugs

### Before Reporting

1. Check if the bug has already been reported
2. Verify it's reproducible on the latest version
3. Collect relevant information (logs, screenshots, steps)

### Bug Report Template

Use the [Bug Report template](.github/ISSUE_TEMPLATE/bug_report.md) and include:

- **Description**: Clear description of the bug
- **Steps to Reproduce**: Detailed steps to reproduce
- **Expected Behavior**: What should happen
- **Actual Behavior**: What actually happens
- **Environment**: Android version, device, app version
- **Logs**: Relevant logcat output or error messages
- **Screenshots**: If applicable

## 💡 Suggesting Features

### Feature Request Process

1. Check if the feature has been requested
2. Use the [Feature Request template](.github/ISSUE_TEMPLATE/feature_request.md)
3. Provide clear use cases and benefits
4. Be open to discussion and feedback

## 🔒 Security Issues

**DO NOT** open public issues for security vulnerabilities.

Instead:
1. Email: security@[domain] (to be configured)
2. Include: Detailed description, steps to reproduce, impact assessment
3. We will respond within 48 hours
4. We will work with you on a fix and disclosure timeline

## 📋 Code Style Guidelines

### Kotlin (Android)

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful variable and function names
- Add KDoc comments for public APIs
- Keep functions focused and small
- Use dependency injection (Hilt)

### Python (Training)

- Follow [PEP 8](https://pep8.org/)
- Use type hints where appropriate
- Add docstrings for functions and classes
- Keep functions under 50 lines when possible
- Use meaningful variable names

### Documentation

- Use clear, concise language
- Include code examples where helpful
- Keep documentation up-to-date with code changes
- Use proper Markdown formatting

## 🧪 Testing Guidelines

### Android Tests

- Write unit tests for business logic
- Write instrumentation tests for UI and integration
- Aim for >80% code coverage for critical paths
- Use MockK for mocking in Kotlin tests

### Python Tests

- Write unit tests using pytest
- Test edge cases and error conditions
- Use fixtures for test data
- Mock external dependencies

## 📚 Documentation

### What to Document

- New features and APIs
- Breaking changes
- Configuration options
- Architecture decisions
- Known limitations

### Where to Document

- Code comments for implementation details
- README.md for high-level overview
- `docs/` for detailed guides
- CHANGELOG.md for version history
- Inline KDoc/docstrings for APIs

## 🎯 Contribution Areas

We especially welcome contributions in:

- **Bug Fixes**: Fixing reported issues
- **Performance**: Optimizing extraction speed
- **Accuracy**: Improving field detection
- **Testing**: Adding test coverage
- **Documentation**: Improving guides and examples
- **Accessibility**: Making the app more accessible
- **Internationalization**: Adding language support

## 🤔 Questions?

- Open a [Discussion](https://github.com/chetank2/coupontracker/discussions)
- Check existing [documentation](docs/)
- Review [closed issues](https://github.com/chetank2/coupontracker/issues?q=is%3Aissue+is%3Aclosed)

## 📜 Code of Conduct

This project adheres to the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

## 🙏 Recognition

Contributors will be recognized in:
- Release notes
- Contributors list
- Project documentation

Thank you for contributing to CouponTracker! 🎉

