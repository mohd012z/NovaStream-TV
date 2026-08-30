import os
from openai import OpenAI

# Initialize OpenAI client with API key from environment variables
client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))

class ChatGPTAgent:
    def __init__(self, model="gpt-4"):
        self.model = model
        self.conversation_history = []
    
    def chat(self, user_message: str) -> str:
        """Send a message to ChatGPT and get a response"""
        self.conversation_history.append({
            "role": "user",
            "content": user_message
        })
        
        response = client.chat.completions.create(
            model=self.model,
            messages=self.conversation_history
        )
        
        assistant_message = response.choices[0].message.content
        self.conversation_history.append({
            "role": "assistant",
            "content": assistant_message
        })
        
        return assistant_message
    
    def reset_conversation(self):
        """Clear conversation history"""
        self.conversation_history = []

# Example usage
if __name__ == "__main__":
    agent = ChatGPTAgent()
    response = agent.chat("Hello, how can you help with NovaStream TV?")
    print(response)
