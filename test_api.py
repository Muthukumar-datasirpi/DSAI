import os
import json
import re
from typing import Dict, Any, List
from openai import OpenAI
import streamlit as st
import psycopg2
import requests

# =========================
# Configuration
# =========================
MODEL = "gpt-4.1-nano"  # Updated to use GPT-4 for better understanding
client = OpenAI(api_key="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")
# DB Config
DB_CONFIG = {
    'host': "keycloak-trojan.ctq5eqck9nph.ap-south-1.rds.amazonaws.com",
    'database': "workdesk_dev_db",
    'user': "postgres",
    'password': "Kcas4KCx754hretTxssEuhawY",
    'port': "5432"
}

API_JSON_PATH = "api_details.json"  # must exist

def get_tenant_issues_from_db(search_term: str = None, tenant_id: str = "40c1b80f-7071-4cf6-8a06-cda221ff3f4d") -> List[Dict[str, Any]]:
    """Query issues from the tenant-specific table with optional search term"""
    try:
        conn = psycopg2.connect(**DB_CONFIG)
        cursor = conn.cursor()
        
        if search_term:
            query = f"""
                SELECT issue_id, title 
                FROM "tenant_{tenant_id}".issue 
                WHERE title ILIKE %s AND is_deleted = false
                ORDER BY created_at DESC
            """
            cursor.execute(query, (f'%{search_term}%',))
        else:
            query = f"""
                SELECT issue_id, title 
                FROM "tenant_{tenant_id}".issue 
                WHERE is_deleted = false
                ORDER BY created_at DESC
            """
            cursor.execute(query)
            
        results = cursor.fetchall()
        return [{"id": str(row[0]), "title": row[1]} for row in results]
        
    except Exception as e:
        st.error(f"Database query failed: {e}")
        return []
    finally:
        if conn:
            conn.close()

def get_issue_details(issue_id: str, tenant_id: str = "40c1b80f-7071-4cf6-8a06-cda221ff3f4d") -> Dict[str, Any]:
    """Get complete details of a specific issue"""
    try:
        conn = psycopg2.connect(**DB_CONFIG)
        cursor = conn.cursor()
        
        query = f"""
            SELECT * 
            FROM "tenant_{tenant_id}".issue 
            WHERE issue_id = %s AND is_deleted = false
        """
        cursor.execute(query, (issue_id,))
        
        # Get column names
        col_names = [desc[0] for desc in cursor.description]
        result = cursor.fetchone()
        
        if result:
            return dict(zip(col_names, result))
        return {}
        
    except Exception as e:
        st.error(f"Database query failed: {e}")
        return {}
    finally:
        if conn:
            conn.close()

# =========================
# Utility functions
# =========================
def load_api_catalog(path: str) -> List[Dict[str, Any]]:
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    apis = data.get("apis", [])
    if not isinstance(apis, list):
        raise ValueError("api_details.json has no 'apis' list")
    return apis

def parse_required_field(field_str: str) -> Dict[str, Any]:
    name = field_str.split(" ", 1)[0].strip()
    m = re.search(r"\((.*?)\)", field_str)
    meta = m.group(1) if m else ""
    meta_lower = meta.lower()
    where = None
    for key in ["path", "query", "header", "from header", "from request body", "body", "request body"]:
        if key in meta_lower:
            where = "path" if "path" in key else \
                    "query" if "query" in key else \
                    "header" if "header" in key else "body"
            break
    if where is None:
        where = "body"
    is_required = "required" in meta_lower and "required if present" not in meta_lower
    required_if_present = "required if present" in meta_lower
    type_hint = None
    if "uuid" in meta_lower: type_hint = "uuid"
    elif "date" in meta_lower: type_hint = "date"
    elif "long" in meta_lower: type_hint = "long"
    elif "int" in meta_lower: type_hint = "int"
    elif "max 255" in meta_lower: type_hint = "string<=255"
    elif "max 300" in meta_lower: type_hint = "string<=300"

    return {
        "name": name,
        "where": where,
        "required": is_required,
        "required_if_present": required_if_present,
        "type_hint": type_hint,
        "raw": field_str
    }

def index_catalog(apis: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    catalog = []
    for entry in apis:
        endpoint = entry.get("endpoint", "")
        desc = entry.get("description", "")
        req_fields = entry.get("required_fields", [])
        parts = endpoint.split(" ", 1)
        if len(parts) != 2:
            continue
        method, path = parts[0].strip(), parts[1].strip()
        parsed_fields = [parse_required_field(s) for s in req_fields]
        catalog.append({
            "method": method,
            "path": path,
            "description": desc,
            "required_fields_raw": req_fields,
            "required_fields": parsed_fields,
            "raw": entry
        })
    return catalog

def intent_to_methods(user_text: str) -> List[str]:
    t = user_text.lower()
    if any(k in t for k in ["create", "add", "open", "log", "new", "raise"]):
        return ["POST", "PUT"]
    if any(k in t for k in ["update", "edit", "change", "modify", "move", "set"]):
        return ["PUT", "PATCH", "POST"]
    if any(k in t for k in ["delete", "remove", "drop"]):
        return ["DELETE"]
    if any(k in t for k in ["get", "fetch", "read", "list", "show", "find", "search", "view"]):
        return ["GET"]
    return ["POST", "PUT", "PATCH", "DELETE", "GET"]

def shortlist_endpoints(catalog: List[Dict[str, Any]], methods: List[str]) -> List[Dict[str, Any]]:
    return [e for e in catalog if e["method"] in methods]

def call_model(messages: List[Dict[str, str]]) -> str:
    resp = client.chat.completions.create(
        model=MODEL,
        messages=messages,
        temperature=0.2
    )
    return resp.choices[0].message.content

def build_selection_prompt(user_utterance: str, base_url: str, candidates: List[Dict[str, Any]], chat_history: List[Dict[str, str]] = []) -> str:
    history_str = "\n".join([f"{msg['role']}: {msg['content']}" for msg in chat_history[-4:]]) if chat_history else "No history yet"
    
    return f"""
Base API Domain: {base_url}

Conversation History:
{history_str}

User request:
{user_utterance}

Endpoints:
{json.dumps([
    {
        "method": c["method"],
        "path": c["path"],
        "description": c["description"],
        "required_fields": c["required_fields"]
    } for c in candidates
], indent=2)}

Instructions:
1. Analyze the user request and conversation history to understand the intent
2. Select the most appropriate endpoint
3. Extract all possible values from the user's request that can fill required fields
4. For missing fields, generate appropriate prompts to ask the user
5. Structure your response as JSON

Return JSON:
{{
  "intent": "brief description of what user wants to do",
  "selected": {{"method": "...", "path": "...", "description": "..."}},
  "extracted_values": {{
    "path": {{"field1": "value1", ...}},
    "query": {{"field1": "value1", ...}},
    "header": {{"field1": "value1", ...}},
    "body": {{"field1": "value1", ...}}
  }},
  "missing_fields": [
    {{
      "name": "field_name",
      "where": "path|query|header|body",
      "type_hint": "data type if specified",
      "prompt": "natural language question to ask user for this field"
    }}
  ],
  "response_to_user": "A friendly response summarizing what we're doing and asking for missing info"
}}
"""

def build_payload(base_url: str, selection: Dict[str, Any], filled: Dict[str, Any]) -> Dict[str, Any]:
    path = selection["selected"]["path"]
    method = selection["selected"]["method"]
    url = base_url.rstrip("/") + path
    
    # Replace path parameters
    if "path" in filled:
        for k, v in filled["path"].items():
            # Handle both {param} and :param style placeholders
            url = url.replace("{" + k + "}", str(v))
            url = url.replace(":" + k, str(v))
    
    return {
        "method": method,
        "url": url,
        "headers": filled.get("header", {}),
        "query": filled.get("query", {}),
        "body": filled.get("body", {})
    }

def extract_values_from_text(user_text: str, endpoint_info: Dict[str, Any]) -> Dict[str, Any]:
    """Use LLM to extract values from natural language text"""
    fields_info = endpoint_info["required_fields"]
    fields_str = "\n".join([f"{f['name']} ({f['where']}): {f.get('type_hint', 'any')}" for f in fields_info])
    
    prompt = f"""
User request: {user_text}

Available fields for endpoint {endpoint_info['method']} {endpoint_info['path']}:
{fields_str}

Extract all possible values from the user's request that can fill these fields.
Return as JSON with fields grouped by their location (path, query, header, body).

Example:
{{
  "path": {{"projectId": "123"}},
  "body": {{"title": "High priority bug", "priority": "high"}}
}}

Your response (only JSON):
"""
    
    response = call_model([
        {"role": "system", "content": "You are an expert at extracting structured data from natural language."},
        {"role": "user", "content": prompt}
    ])
    
    try:
        return json.loads(response)
    except:
        return {}

# =========================
# Streamlit UI
# =========================
st.set_page_config(page_title="GoDesk API Chatbot", layout="wide")
st.title("💬 GoDesk API Chatbot")

# Initialize session state with additional tracking variables
if "catalog" not in st.session_state:
    try:
        apis = load_api_catalog(API_JSON_PATH)
        st.session_state.catalog = index_catalog(apis)
    except Exception as e:
        st.error(f"Error loading API catalog: {e}")
        st.stop()

if "messages" not in st.session_state:
    st.session_state.messages = [{"role": "assistant", "content": "Hi! I'm your GoDesk API assistant. How can I help you today?"}]

if "in_progress" not in st.session_state:
    st.session_state.in_progress = False

if "current_selection" not in st.session_state:
    st.session_state.current_selection = None

if "extracted_values" not in st.session_state:
    st.session_state.extracted_values = {"path": {}, "query": {}, "header": {}, "body": {}}

if "awaiting_confirmation" not in st.session_state:
    st.session_state.awaiting_confirmation = False
    
if "awaiting_project_id" not in st.session_state:
    st.session_state.awaiting_project_id = False

if "awaiting_issue_selection" not in st.session_state:
    st.session_state.awaiting_issue_selection = False
    
if "matched_issues" not in st.session_state:
    st.session_state.matched_issues = []

if "selected_issue_id" not in st.session_state:
    st.session_state.selected_issue_id = None

if "show_radio_selection" not in st.session_state:
    st.session_state.show_radio_selection = False

if "awaiting_update_selection" not in st.session_state:
    st.session_state.awaiting_update_selection = False
    
if "awaiting_field_update" not in st.session_state:
    st.session_state.awaiting_field_update = False
    
if "current_issue_details" not in st.session_state:
    st.session_state.current_issue_details = {}
    
if "fields_to_update" not in st.session_state:
    st.session_state.fields_to_update = {}
    
if "update_mode" not in st.session_state:
    st.session_state.update_mode = False

DEFAULT_HEADERS = {
    "Content-Type": "application/json",
    "X-Tenant-Id": "techy",
    "Authorization": "Bearer eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJSRGlRYXdqWXZqaS1OMGVEZ2pTZG9SVGl3S21GVGdZZlphM0dVSmVPejlFIn0.eyJleHAiOjE3NTU4NjQzMDgsImlhdCI6MTc1NTgyODMwOSwiYXV0aF90aW1lIjoxNzU1ODI4MzA4LCJqdGkiOiJjYWYwYTJhZi0wYTNjLTQ4ZDAtODdiMC0yOTBkYzA0YjE1MTkiLCJpc3MiOiJodHRwczovL2xvZ2luLWRldi5kYXRhc2lycGkuY29tL3JlYWxtcy9kcy1uZXN0IiwiYXVkIjoiYWNjb3VudCIsInN1YiI6IjVjYmFjN2U4LWFjZWEtNGEyYy1iMjljLTI1MzIwZjhkMDVlYyIsInR5cCI6IkJlYXJlciIsImF6cCI6IjBmYmIyYTFhLTUyY2QtNDAxNS04YTQ4LTlhNmEzOWE0N2M2MyIsIm5vbmNlIjoiMGNmYTBhMmQtODU3OS00MTM4LWFiMTAtM2UzOTgyNGVmZDM5Iiwic2Vzc2lvbl9zdGF0ZSI6IjY1NTMzYzU0LWM0NDctNDZkNS1hMzVjLTk2NzQzNmU1ODk3NCIsImFjciI6IjEiLCJhbGxvd2VkLW9yaWdpbnMiOlsiKiJdLCJyZWFsbV9hY2Nlc3MiOnsicm9sZXMiOlsiZGVmYXVsdC1yb2xlcy1kcy1uZXN0Iiwib2ZmbGluZV9hY2Nlc3MiLCJ1bWFfYXV0aG9yaXphdGlvbiJdfSwicmVzb3VyY2VfYWNjZXNzIjp7ImFjY291bnQiOnsicm9sZXMiOlsibWFuYWdlLWFjY291bnQiLCJtYW5hZ2UtYWNjb3VudC1saW5rcyIsInZpZXctcHJvZmlsZSJdfX0sInNjb3BlIjoib3BlbmlkIGVtYWlsIHByb2ZpbGUiLCJzaWQiOiI2NTUzM2M1NC1jNDQ3LTQ2ZDUtYTM1Yy05Njc0MzZlNTg5NzQiLCJlbWFpbF92ZXJpZmllZCI6dHJ1ZSwiYXBwX3VzZXJfaWQiOiIzZDRhOTlkZi1mMzE3LTRkYmYtYjUwMi1lNjZjYjc4ZjM3YTEiLCJtb2JpbGVOdW1iZXIiOiIiLCJuYW1lIjoiSmViYSBQcmlza2lsbGFsIiwiQ2xpZW50SWQiOiJ0ZWNoeS1jbGllbnQiLCJwcmVmZXJyZWRfdXNlcm5hbWUiOiJqZWJhcHJpc2tpbGxhbC5rQGRhdGFzaXJwaS5jb20iLCJnaXZlbl9uYW1lIjoiSmViYSIsImZhbWlseV9uYW1lIjoiUHJpc2tpbGxhbCIsImVtYWlsIjoiamViYXByaXNraWxsYWwua0BkYXRhc2lycGkuY29tIn0.gfoJpMRVyulNM7Pel5EoHa0nFQNKitlp7d0T69YvtooDXlOUgwsPtownfUUaxZZCYjaa9q4lnpeO6q9GXYKBLdCgTJbiVY2G3qFJFMvbgHbsesV5Mhok0DVbjBxdeQwHBLYuZhCuRSKwdXlHAnpFdNqebC3JK4eC22FzJW47JoYHfRyaaK_jeZTeI_A9OdWEOfchA3sIsj84RJRLchgweHBaDaJ9PFEELYa_F_DJoSKJD3YqRmW-dASdJbBh7m6BURmkEd78Zus3tAZ9RcR77E-RvNv7NQPOhiCpjj5Lt87pQcRg805NiYMn6zTRMn3LYWP0QWU2z-Zevo7CLie55A"
}

# Display chat messages
for message in st.session_state.messages:
    with st.chat_message(message["role"]):
        st.markdown(message["content"])

def execute_api_request(payload: Dict[str, Any]):
    try:
        # Make the API request
        if payload["method"] == "GET":
            resp = requests.get(
                payload["url"],
                headers=payload["headers"],
                params=payload["query"]
            )
        elif payload["method"] == "DELETE":
            resp = requests.delete(
                payload["url"],
                headers=payload["headers"],
                params=payload["query"],
                json=payload.get("body", {})
            )
        elif payload["method"] in ["PUT", "PATCH"]:
            resp = requests.request(
                payload["method"],
                payload["url"],
                headers=payload["headers"],
                json=payload.get("body", {})
            )
        else:
            resp = requests.request(
                payload["method"],
                payload["url"],
                headers=payload["headers"],
                params=payload["query"],
                json=payload.get("body", {})
            )
        
        # Handle response
        response_content = ""
        try:
            response_json = resp.json()
            response_content = json.dumps(response_json, indent=2)
        except:
            response_content = resp.text
            
        if resp.status_code >= 400:
            error_msg = f"API request failed with status {resp.status_code}\nResponse: {response_content}"
            st.session_state.messages.append({
                "role": "assistant",
                "content": error_msg
            })
            return False
        else:
            success_msg = f"✅ API request completed successfully (Status: {resp.status_code})"
            st.session_state.messages.append({
                "role": "assistant",
                "content": success_msg
            })
            return True
            
    except Exception as e:
        error_msg = f"Request failed: {str(e)}"
        st.session_state.messages.append({
            "role": "assistant",
            "content": error_msg
        })
        return False
# NEW FUNCTION FOR UPDATE ISSUE FLOW
def handle_update_issue_flow(issue_id: str):
    """Handle the complete update issue workflow"""
    # Get issue details from database
    issue_details = get_issue_details(issue_id)
    if not issue_details:
        st.session_state.messages.append({
            "role": "assistant",
            "content": "Could not find issue details. Please try again."
        })
        return
    
    st.session_state.current_issue_details = issue_details
    st.session_state.awaiting_field_update = True
    
    # Show current issue details
    st.session_state.messages.append({
        "role": "assistant",
        "content": f"Found issue: {issue_details.get('title', 'Unknown')}\n\nCurrent details:\n{json.dumps({k: v for k, v in issue_details.items() if v is not None}, indent=2, default=str)}"
    })
    
    # Ask which fields to update
    st.session_state.messages.append({
        "role": "assistant",
        "content": "Which fields would you like to update? (comma-separated, e.g., 'title, description, priority')"
    })

# DISPLAY UPDATE SELECTION UI
if st.session_state.awaiting_update_selection and st.session_state.matched_issues:
    st.write("---")
    st.write("### 🎯 Select a ticket to update:")
    
    issue_options = [f"{issue['title']} (ID: {issue['id']})" for issue in st.session_state.matched_issues]
    issue_ids = [issue['id'] for issue in st.session_state.matched_issues]
    
    selected_issue_index = st.radio(
        "Choose the ticket you want to update:",
        options=range(len(issue_options)),
        format_func=lambda i: issue_options[i],
        key="update_selection_radio"
    )
    
    col1, col2 = st.columns(2)
    
    with col1:
        if st.button("✅ Select to Update", type="primary", key="select_update"):
            selected_issue_id = issue_ids[selected_issue_index]
            handle_update_issue_flow(selected_issue_id)
            st.session_state.awaiting_update_selection = False
            st.rerun()
    
    with col2:
        if st.button("❌ Cancel Update", key="cancel_update"):
            st.session_state.awaiting_update_selection = False
            st.session_state.matched_issues = []
            st.session_state.messages.append({
                "role": "assistant",
                "content": "Update operation cancelled."
            })
            st.rerun()

# DISPLAY FIELD UPDATE UI
if st.session_state.awaiting_field_update and st.session_state.current_issue_details:
    st.write("---")
    st.write("### ✏️ Update Issue Fields")
    
    issue_details = st.session_state.current_issue_details
    
    # Let user select which fields to update
    available_fields = [k for k, v in issue_details.items() if v is not None and k not in ['issue_id', 'created_at', 'updated_at', 'is_deleted']]
    selected_fields = st.multiselect(
        "Select fields to update:",
        options=available_fields,
        default=list(st.session_state.fields_to_update.keys())
    )
    
    # Create input fields for each selected field
    for field in selected_fields:
        current_value = issue_details.get(field, '')
        new_value = st.text_input(
            f"New value for {field}:",
            value=st.session_state.fields_to_update.get(field, str(current_value)),
            key=f"update_field_{field}"
        )
        st.session_state.fields_to_update[field] = new_value
    
    col1, col2 = st.columns(2)
    
    with col1:
        if st.button("💾 Save Updates", type="primary", key="save_updates"):
            # Build update payload
            update_payload = {
                "method": "PUT",
                "url": f"https://dev-workdesk.datasirpi.com/management-service/api/v1/issues/{issue_details['issue_id']}",
                "headers": DEFAULT_HEADERS,
                "body": st.session_state.fields_to_update
            }
            
            if execute_api_request(update_payload):
                st.session_state.awaiting_field_update = False
                st.session_state.fields_to_update = {}
                st.session_state.current_issue_details = {}
                st.rerun()
    
    with col2:
        if st.button("❌ Cancel Update", key="cancel_field_update"):
            st.session_state.awaiting_field_update = False
            st.session_state.fields_to_update = {}
            st.session_state.current_issue_details = {}
            st.session_state.messages.append({
                "role": "assistant",
                "content": "Field update cancelled."
            })
            st.rerun()

# Display issue selection radio if awaiting selection
if st.session_state.awaiting_issue_selection and st.session_state.matched_issues:
    st.write("---")
    st.write("### 🎯 Select an ticket to delete:")
    
    # Create radio options with issue titles
    issue_options = [f"{issue['title']} (ID: {issue['id']})" for issue in st.session_state.matched_issues]
    issue_ids = [issue['id'] for issue in st.session_state.matched_issues]
    
    selected_issue_index = st.radio(
        "Choose the ticket you want to delete:",
        options=range(len(issue_options)),
        format_func=lambda i: issue_options[i],
        key="issue_selection_radio"
    )
    
    col1, col2 = st.columns(2)
    
    with col1:
        if st.button("✅ Confirm Delete", type="primary"):
            selected_issue_id = issue_ids[selected_issue_index]
            
            # Store the selected issue ID in path parameters
            st.session_state.extracted_values["path"]["id"] = selected_issue_id
            
            # Build and execute the payload
            payload = build_payload(
                "https://dev-workdesk.datasirpi.com/management-service",
                st.session_state.current_selection,
                st.session_state.extracted_values
            )
            
            if execute_api_request(payload):
                st.session_state.awaiting_issue_selection = False
                st.session_state.matched_issues = []
                st.session_state.show_radio_selection = False
                st.rerun()
    
    with col2:
        if st.button("❌ Cancel"):
            st.session_state.awaiting_issue_selection = False
            st.session_state.matched_issues = []
            st.session_state.show_radio_selection = False
            st.session_state.messages.append({
                "role": "assistant",
                "content": "Delete operation cancelled."
            })
            st.rerun()

# Chat input - only show if not in selection mode
if not st.session_state.awaiting_issue_selection and not st.session_state.awaiting_update_selection and not st.session_state.awaiting_field_update:
    if prompt := st.chat_input("Type your request..."):
        # Add user message to chat history
        st.session_state.messages.append({"role": "user", "content": prompt})
        
        # Display user message
        with st.chat_message("user"):
            st.markdown(prompt)
            
        # Handle UPDATE intent
        if any(word in prompt.lower() for word in ["update", "edit", "change", "modify"]):
            # Search for issues to update
            issues = get_tenant_issues_from_db(prompt)
            if issues:
                st.session_state.awaiting_update_selection = True
                st.session_state.matched_issues = issues
                st.session_state.messages.append({
                    "role": "assistant",
                    "content": f"I found {len(issues)} matching issue(s). Please select which one to update."
                })
            else:
                st.session_state.messages.append({
                    "role": "assistant",
                    "content": "No matching issues found to update. Please provide a more specific ticket name."
                })
        
        # Handle project ID input
        if st.session_state.awaiting_project_id:
            # Add project ID to headers
            st.session_state.extracted_values["header"]["X-Project-Id"] = prompt.strip()
            st.session_state.awaiting_project_id = False
            payload = build_payload(
                "https://dev-workdesk.datasirpi.com/management-service",
                st.session_state.current_selection,
                st.session_state.extracted_values
            )
            execute_api_request(payload)
            st.rerun()
        
        # Set in progress flag
        st.session_state.in_progress = True
        
        # Process new user input
        methods = intent_to_methods(prompt)
        candidates = shortlist_endpoints(st.session_state.catalog, methods) or st.session_state.catalog
        
        # Get API selection and field extraction
        selection_prompt = build_selection_prompt(
            prompt, 
            "https://dev-workdesk.datasirpi.com/management-service",
            candidates,
            st.session_state.messages
        )
        
        model_response = call_model([
            {"role": "system", "content": "You are a helpful API assistant that helps users interact with GoDesk APIs."},
            {"role": "user", "content": selection_prompt}
        ])
        
        try:
            # Extract JSON from model response
            m = re.search(r'(\{.*\})', model_response, re.DOTALL)
            if m:
                model_json = m.group(1)
                selection = json.loads(model_json)
            else:
                selection = json.loads(model_response)
            
            # Store selection and extracted values
            st.session_state.current_selection = selection
            st.session_state.extracted_values = selection.get("extracted_values", {"path": {}, "query": {}, "header": {}, "body": {}})
            
            # Merge default headers
            for hk, hv in DEFAULT_HEADERS.items():
                if hk not in st.session_state.extracted_values["header"]:
                    st.session_state.extracted_values["header"][hk] = hv
                    
            if "update" in selection.get("intent", "").lower():
                if "issueId" not in st.session_state.extracted_values.get("path", {}):
                    issues = get_tenant_issues_from_db(prompt)
                    if issues:
                        st.session_state.awaiting_update_selection = True
                        st.session_state.matched_issues = issues
                        selection["response_to_user"] = f"I found {len(issues)} matching issue(s). Please select which one to update."
        
                st.rerun()
            
            # Handle DELETE operations specifically
            if "delete" in selection.get("intent", "").lower():
                # Check if we have an issue title instead of ID
                if "issueId" not in st.session_state.extracted_values.get("path", {}) and \
                "issueId" not in st.session_state.extracted_values.get("body", {}):
                    # Try to find title in the request
                    issues = get_tenant_issues_from_db(prompt)
                    if issues:
                        # Set state to show radio selection
                        st.session_state.awaiting_issue_selection = True
                        st.session_state.matched_issues = issues
                        st.session_state.show_radio_selection = True
                        
                        selection["response_to_user"] = (
                            f"I found {len(issues)} matching issue(s). Please select which one to delete from the options below."
                        )
                    else:
                        selection["response_to_user"] = (
                            "I couldn't find any matching active tickets with that name. "
                            "Please provide the ticket name you want to delete."
                        )
                
                # Existing project ID handling...
                needs_project_id = any(f["name"].lower() == "projectid" and f["where"] == "header" 
                                    for f in selection.get("missing_fields", []))
                
                if needs_project_id and not st.session_state.awaiting_issue_selection:
                    st.session_state.awaiting_confirmation = True
                    selection["response_to_user"] = (
                        f"I will proceed to delete the issue. "
                        "Would you like to include a project ID header?"
                    )
            
            # Add assistant response to chat
            st.session_state.messages.append({
                "role": "assistant", 
                "content": selection.get("response_to_user", "I'll help you with that.")
            })
            
            # If we're showing radio selection, don't show the execute button
            if not st.session_state.awaiting_issue_selection and not selection.get("missing_fields") and not st.session_state.awaiting_confirmation:
                payload = build_payload(
                    "https://dev-workdesk.datasirpi.com/management-service",
                    selection,
                    st.session_state.extracted_values
                )
                
                # Show preview and execute button
                with st.chat_message("assistant"):
                    st.markdown("Here's what I'm about to execute:")
                    st.json(payload)
                    
                    if st.button("Execute API Request"):
                        if execute_api_request(payload):
                            st.rerun()
            
        except Exception as e:
            error_msg = f"Request failed: {str(e)}"
            st.session_state.messages.append({
                "role": "assistant",
                "content": error_msg
            })
        st.rerun()
        
        # Reset progress flag
        st.session_state.in_progress = False