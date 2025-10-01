// Get references to the HTML elements we'll be working with
const searchInput = document.getElementById('searchInput');
const suggestionsList = document.getElementById('suggestionsList');

// Add an event listener that triggers every time the user types in the input field
searchInput.addEventListener('input', async (event) => {
    // Get the current text from the input box and remove leading/trailing whitespace
    const prefix = event.target.value.trim();

    // If the input is empty, clear the suggestions and hide the list
    if (prefix.length === 0) {
        suggestionsList.innerHTML = '';
        suggestionsList.style.display = 'none';
        return;
    }

    // Fetch suggestions from our Spring Boot backend
    try {
        // Construct the URL for the API call
        const url = `/autocomplete?prefix=${encodeURIComponent(prefix)}`;

        // Use the fetch API to make a GET request to the backend
        const response = await fetch(url);

        // Check if the request was successful
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        // Parse the JSON response (which should be a list of strings)
        const suggestions = await response.json();

        // Display the suggestions
        displaySuggestions(suggestions);

    } catch (error) {
        console.error("Failed to fetch suggestions:", error);
        // Clear the list in case of an error
        suggestionsList.innerHTML = '';
        suggestionsList.style.display = 'none';
    }
});

/**
 * Renders the list of suggestions on the page.
 * @param {string[]} suggestions - An array of suggestion strings.
 */
function displaySuggestions(suggestions) {
    // Clear any old suggestions
    suggestionsList.innerHTML = '';

    // If there are no suggestions, hide the list and stop
    if (suggestions.length === 0) {
        suggestionsList.style.display = 'none';
        return;
    }

    // Create a list item (<li>) for each suggestion and add it to the list
    suggestions.forEach(word => {
        const li = document.createElement('li');
        li.textContent = word;

        // Add a click event to each suggestion
        // When clicked, it puts the word in the search box and clears the list
        li.addEventListener('click', () => {
            searchInput.value = word;
            suggestionsList.innerHTML = '';
            suggestionsList.style.display = 'none';
        });

        suggestionsList.appendChild(li);
    });

    // Make the suggestions list visible
    suggestionsList.style.display = 'block';
}

// Optional: Hide the suggestions if the user clicks anywhere else on the page
document.addEventListener('click', (event) => {
    if (event.target !== searchInput) {
        suggestionsList.style.display = 'none';
    }
});