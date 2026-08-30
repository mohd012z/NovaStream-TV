from agents.chatgpt_agent import ChatGPTAgent

# Test connection to ChatGPT
if __name__ == "__main__":
    print("Testing ChatGPT connection...")
    agent = ChatGPTAgent()
    
    try:
        response = agent.chat("Salam, boleh anda bantu saya?")
        print(f"✅ ChatGPT Response: {response}")
    except Exception as e:
        print(f"❌ Error: {str(e)}")
