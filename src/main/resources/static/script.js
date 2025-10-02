// Get references to the HTML elements we'll be working with
const searchInput = document.getElementById('searchInput'); // Ensure your HTML input has id="searchInput"
const suggestionsList = document.getElementById('suggestionsList'); // Ensure your HTML list/div has id="suggestionsList"

// Add an event listener that triggers every time the user types in the input field
searchInput.addEventListener('input', async (event) => {
    // Get the current text from the input box
    const prefix = event.target.value.trim();

    // If the input is empty, clear the suggestions and hide the list
    if (prefix.length === 0) {
        suggestionsList.innerHTML = '';
        suggestionsList.style.display = 'none';
        return;
    }

    try {
        // --- THIS IS THE CRITICAL LINE FOR DEPLOYMENT ---
        // By using a relative path `/autocomplete`, the browser will automatically
        // send the request to the same server that served the webpage.
        const url = `/autocomplete?prefix=${encodeURIComponent(prefix)}`;

        const response = await fetch(url);

        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        const suggestions = await response.json();
        displaySuggestions(suggestions);

    } catch (error) {
        console.error("Failed to fetch suggestions:", error);
        suggestionsList.innerHTML = '';
        suggestionsList.style.display = 'none';
    }
});

/**
 * Renders the list of suggestions on the page.
 * @param {string[]} suggestions - An array of suggestion strings.
 */
function displaySuggestions(suggestions) {
    suggestionsList.innerHTML = '';

    if (suggestions.length === 0) {
        suggestionsList.style.display = 'none';
        return;
    }

    suggestions.forEach(word => {
        const li = document.createElement('li');
        li.textContent = word;

        li.addEventListener('click', () => {
            searchInput.value = word;
            suggestionsList.innerHTML = '';
            suggestionsList.style.display = 'none';
        });

        suggestionsList.appendChild(li);
    });

    suggestionsList.style.display = 'block';
}

// Optional: Hide the suggestions if the user clicks anywhere else on the page
document.addEventListener('click', (event) => {
    if (event.target !== searchInput) {
        suggestionsList.style.display = 'none';
    }
});