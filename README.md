# NovaStream TV

under dev

## ChatGPT Integration Setup

### Prerequisites
- Python 3.8+
- OpenAI API key

### Installation

1. Clone the repository
2. Install dependencies:
```bash
pip install -r agents/requirements.txt
```

3. Create a `.env` file in the root directory:
```
OPENAI_API_KEY=your_actual_api_key_here
OPENAI_MODEL=gpt-4
```

4. **Never commit the `.env` file** - it's in `.gitignore`

### Usage

```python
from agents.chatgpt_agent import ChatGPTAgent

agent = ChatGPTAgent()
response = agent.chat("Your question here")
print(response)
```

### Security Best Practices
- ✅ Store API keys in `.env` file (not in code)
- ✅ Add `.env` to `.gitignore`
- ✅ Regenerate exposed keys immediately
- ✅ Rotate keys periodically
- ✅ Monitor API usage in OpenAI dashboard
