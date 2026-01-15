import pygame
import cv2
import mediapipe as mp
import time
import math
import sys
import random

# --- Pygame Initialization ---
pygame.init()

# --- Game Setup ---

# Get screen information for full-screen mode
info = pygame.display.Info()
SCREEN_WIDTH = info.current_w
SCREEN_HEIGHT = info.current_h

# ADJUSTED POSITIONS (Common to both games)
GAME_WIDTH = 1000 # INCREASED WIDTH (was 600)
GAME_HEIGHT = SCREEN_HEIGHT - 100 
GAME_X = 50 
GAME_Y = 50 

WEBCAM_WIDTH = 300
WEBCAM_HEIGHT = 225

SCOREBOARD_WIDTH = 350
SCOREBOARD_X = SCREEN_WIDTH - SCOREBOARD_WIDTH - 50 
SCOREBOARD_Y = GAME_Y

# Position webcam view between the game and scoreboard
# This calculation dynamically centers the webcam display between the expanded game area and the scoreboard.
WEBCAM_DISPLAY_X = GAME_X + GAME_WIDTH + (SCOREBOARD_X - (GAME_X + GAME_WIDTH)) // 2 
WEBCAM_DISPLAY_Y = GAME_Y + WEBCAM_HEIGHT // 2


# --- NEO CYBER THEME COLORS ---
BLACK = (5, 5, 10)
DARK_BLUE = (10, 10, 25) # Deep Space Background
NEON_CYAN = (0, 255, 255) # Primary highlight color
NEON_YELLOW = (255, 255, 0) # Secondary highlight color
NEON_RED = (255, 50, 50)
WHITE = (240, 240, 240)
GRAY = (70, 70, 70)
PURPLE = (150, 50, 255)
SHADOW_COLOR = (0, 0, 0) # Deep shadow

# Use theme colors for components
CYAN = NEON_CYAN
LIGHT_BLUE = (30, 150, 255) # Less intense blue for static elements
YELLOW = NEON_YELLOW
RED = NEON_RED
INPUT_BOX_COLOR = (20, 20, 50)

# Define Gradient Colors
GRADIENT_TOP = (10, 10, 20)
GRADIENT_BOTTOM = (5, 5, 15)

# Game Constants (Gravity Switch)
GS_PLAYER_SIZE = 20
GS_GRAVITY = 5 
GS_LIFT_FORCE = -5 
GS_MAX_FALL_SPEED = 10
GS_OBSTACLE_WIDTH = 30
GS_OBSTACLE_GAP_HEIGHT = 150
GS_OBSTACLE_SPEED = 5
GS_SPAWN_INTERVAL = 4000 

# Game Constants (Asteroid Dodge)
AD_PLAYER_SIZE = 30
AD_PLAYER_X = GAME_X + 50
AD_THRUST_FORCE = -8
AD_DRIFT_SPEED = 4
AD_MAX_SPEED = 10
AD_ASTEROID_SIZE = 100 
AD_ASTEROID_SPEED = 6
AD_SPAWN_INTERVAL = 1500

# Star field for Asteroid Dodge Menu Preview/Simulation
stars = []
for _ in range(100):
    stars.append([random.randint(0, GAME_WIDTH), random.randint(0, GAME_HEIGHT), random.randint(1, 3)])

# Use FULLSCREEN and NOFRAME flags for borderless maximized view
screen = pygame.display.set_mode((SCREEN_WIDTH, SCREEN_HEIGHT), pygame.FULLSCREEN | pygame.NOFRAME)
pygame.display.set_caption("Dual Gesture Games: Rehab Edition")
clock = pygame.time.Clock()
font_title = pygame.font.Font(None, 60)
font_large = pygame.font.Font(None, 48)
font_medium = pygame.font.Font(None, 36)
font_small = pygame.font.Font(None, 24)

# --- MediaPipe and OpenCV Setup ---
mp_hands = mp.solutions.hands
hands = mp_hands.Hands(
    model_complexity=1,
    min_detection_confidence=0.7,
    min_tracking_confidence=0.7
)
mp_drawing = mp.solutions.drawing_utils
cap = None # Webcam capture object

# --- Game State Variables ---
game_state = 'MAIN_MENU' 
current_game = None # 'GRAVITY_SWITCH' or 'ASTEROID_DODGE'
game_running = False 
score = 0
is_hand_open = False
player_name = "Rehab Player" # Default name
active_scores = [] 

# Player Rects (Separate for each game)
gs_player_rect = pygame.Rect(0, 0, GS_PLAYER_SIZE, GS_PLAYER_SIZE)
gs_player_y_velocity = 0
gs_obstacles = []
gs_last_obstacle_spawn = time.time() * 1000
gs_player_trail = [] 

ad_player_rect = pygame.Rect(AD_PLAYER_X, 0, AD_PLAYER_SIZE, AD_PLAYER_SIZE)
ad_player_y_velocity = 0
ad_asteroids = []
ad_last_obstacle_spawn = time.time() * 1000

# --- Input Box State ---
input_box_rect = pygame.Rect(0, 0, 250, 40) 
color_inactive = GRAY
color_active = WHITE
input_box_color = color_inactive
text_active = False

# --- Game Classes (Gravity Switch) ---

class Obstacle:
    def __init__(self, x, gap_y):
        self.x = x
        self.width = GS_OBSTACLE_WIDTH
        self.gap_y = gap_y 
        self.gap_height = GS_OBSTACLE_GAP_HEIGHT
        self.passed = False

    def update(self):
        global game_running
        if game_running:
            self.x -= GS_OBSTACLE_SPEED

    def draw(self, surface):
        if not game_running:
            return

        screen_x = GAME_X + self.x
        top_height = self.gap_y - self.gap_height // 2
        
        # Draw top obstacle with a neon border
        pygame.draw.rect(surface, LIGHT_BLUE, (screen_x, GAME_Y, self.width, top_height))
        pygame.draw.rect(surface, CYAN, (screen_x, GAME_Y, self.width, top_height), 2)


        bottom_y = self.gap_y + self.gap_height // 2
        bottom_height = GAME_HEIGHT - bottom_y
        # Draw bottom obstacle with a neon border
        pygame.draw.rect(surface, LIGHT_BLUE, (screen_x, GAME_Y + bottom_y, self.width, bottom_height))
        pygame.draw.rect(surface, CYAN, (screen_x, GAME_Y + bottom_y, self.width, bottom_height), 2)


    def check_collision(self, player):
        player_x = player.x - GAME_X
        player_y = player.y - GAME_Y

        player_game_rect = pygame.Rect(player_x, player_y, GS_PLAYER_SIZE, GS_PLAYER_SIZE)
        
        if player_game_rect.right > self.x and player_game_rect.left < self.x + self.width:
            
            top_rect = pygame.Rect(self.x, 0, self.width, self.gap_y - self.gap_height // 2)
            bottom_rect = pygame.Rect(self.x, self.gap_y + self.gap_height // 2, self.width, GAME_HEIGHT - (self.gap_y + self.gap_height // 2))

            return player_game_rect.colliderect(top_rect) or player_game_rect.colliderect(bottom_rect)
        return False

# --- Game Classes (Asteroid Dodge) ---

class Asteroid:
    def __init__(self, x, y):
        self.x = x
        self.y = y
        self.size = AD_ASTEROID_SIZE
        self.rect = pygame.Rect(x, y, self.size, self.size)
        self.passed = False

    def update(self):
        global game_running
        if game_running:
            self.x -= AD_ASTEROID_SPEED
            self.rect.x = self.x

    def draw(self, surface):
        center_x = GAME_X + self.x + self.size // 2
        center_y = GAME_Y + self.y + self.size // 2
        
        # Draw dark core
        pygame.draw.circle(surface, GRAY, (center_x, center_y), self.size // 2)
        
        # Draw a jagged, rocky outline for a "fancy" look
        pygame.draw.circle(surface, (120, 120, 120), (center_x, center_y), self.size // 2, 4)
        
        # Draw neon red ring/glow (damage indicator style)
        pygame.draw.circle(surface, RED, (center_x, center_y), self.size // 2 + 5, 2)
        
        # Detail spots
        pygame.draw.circle(surface, DARK_BLUE, (center_x - 15, center_y - 15), 8) 
        pygame.draw.circle(surface, DARK_BLUE, (center_x + 10, center_y + 10), 5) 


    def check_collision(self, player):
        player_game_rect = pygame.Rect(player.x - GAME_X, player.y - GAME_Y, AD_PLAYER_SIZE, AD_PLAYER_SIZE)
        asteroid_game_rect = pygame.Rect(self.x, self.y, self.size, self.size)
        return player_game_rect.colliderect(asteroid_game_rect)

# --- Game Functions (Universal) ---

def simulate_add_score(name, final_score):
    """Simulates adding a score to the persistent list and sorts it."""
    global active_scores
    if final_score > 0 and name and name.strip(): # Only save valid scores with a name
        active_scores.append({'name': name, 'score': final_score, 'game': current_game})
        active_scores.sort(key=lambda x: x['score'], reverse=True)
        active_scores = active_scores[:10] 

def game_over():
    global game_running, game_state, score
    game_running = False
    game_state = 'GAME_OVER'
    simulate_add_score(player_name, score)

# --- Game Functions (Gravity Switch) ---

def reset_gravity_switch():
    global game_running, game_state, score, gs_player_y_velocity, gs_obstacles, gs_last_obstacle_spawn, gs_player_trail, current_game
    
    score = 0
    gs_player_y_velocity = 0
    gs_obstacles = []
    gs_player_trail = []
    current_game = 'GRAVITY_SWITCH'
    
    # Initial position in the game area
    gs_player_rect.x = GAME_X + GAME_WIDTH // 2 - GS_PLAYER_SIZE // 2
    gs_player_rect.y = GAME_Y + GAME_HEIGHT // 2 - GS_PLAYER_SIZE // 2
    
    gs_last_obstacle_spawn = time.time() * 1000 
    game_running = True
    game_state = 'RUNNING'

def spawn_gs_obstacle():
    global gs_last_obstacle_spawn, gs_obstacles
    current_time = time.time() * 1000

    if current_time - gs_last_obstacle_spawn > GS_SPAWN_INTERVAL:
        min_gap_y = 100 
        max_gap_y = GAME_HEIGHT - 100
        
        amplitude_factor = 0.3 
        gap_y = min_gap_y + (max_gap_y - min_gap_y) * (0.5 + amplitude_factor * math.sin(current_time / 1500))
        
        gs_obstacles.append(Obstacle(GAME_WIDTH, int(gap_y)))
        gs_last_obstacle_spawn = current_time

def update_gravity_switch():
    global game_running, score, gs_player_y_velocity, is_hand_open, gs_obstacles, gs_player_trail

    if not game_running:
        return

    spawn_gs_obstacle() 

    # 1. Player Movement (Gravity Switch Logic)
    if is_hand_open:
        gs_player_y_velocity += GS_LIFT_FORCE 
    else:
        gs_player_y_velocity += GS_GRAVITY

    gs_player_y_velocity = max(-GS_MAX_FALL_SPEED, min(gs_player_y_velocity, GS_MAX_FALL_SPEED))
    gs_player_rect.y += gs_player_y_velocity

    gs_player_trail.append(gs_player_rect.center)
    if len(gs_player_trail) > 10: 
        gs_player_trail.pop(0)

    # 2. Obstacle Update and Collision Check
    new_obstacles = []
    for obs in gs_obstacles:
        obs.update()

        if obs.check_collision(gs_player_rect):
            game_over()
            return

        # Use player_rect's game-coordinate x for score check (relative to GAME_X)
        if obs.x + obs.width < gs_player_rect.x - GAME_X and not obs.passed: 
            score += 1
            obs.passed = True

        if obs.x + obs.width > 0:
            new_obstacles.append(obs)
    
    gs_obstacles = new_obstacles

    # 3. Boundary Check (Hit top or bottom of the game area)
    if gs_player_rect.top < GAME_Y or gs_player_rect.bottom > GAME_Y + GAME_HEIGHT:
        game_over()
        return

# --- Game Functions (Asteroid Dodge) ---

def reset_asteroid_dodge():
    global game_running, game_state, score, ad_player_y_velocity, ad_asteroids, ad_last_obstacle_spawn, current_game
    
    score = 0
    ad_player_y_velocity = 0
    ad_asteroids = []
    current_game = 'ASTEROID_DODGE'
    
    # Initial position in the game area (set Y to center)
    ad_player_rect.y = GAME_Y + GAME_HEIGHT // 2 - AD_PLAYER_SIZE // 2
    
    ad_last_obstacle_spawn = time.time() * 1000 
    game_running = True
    game_state = 'RUNNING'

def spawn_ad_asteroid():
    global ad_last_obstacle_spawn, ad_asteroids
    current_time = time.time() * 1000

    if current_time - ad_last_obstacle_spawn > AD_SPAWN_INTERVAL:
        # Random Y position for the asteroid
        max_y = GAME_HEIGHT - AD_ASTEROID_SIZE
        asteroid_y = random.randint(0, max_y)
        
        ad_asteroids.append(Asteroid(GAME_WIDTH, asteroid_y))
        ad_last_obstacle_spawn = current_time

def update_asteroid_dodge():
    global game_running, score, ad_player_y_velocity, is_hand_open, ad_asteroids

    if not game_running:
        return

    spawn_ad_asteroid() 

    # 1. Player Movement (Thrust/Drift Logic)
    if is_hand_open:
        ad_player_y_velocity += AD_THRUST_FORCE 
    else:
        ad_player_y_velocity += AD_DRIFT_SPEED 

    ad_player_y_velocity = max(-AD_MAX_SPEED, min(ad_player_y_velocity, AD_MAX_SPEED))
    ad_player_rect.y += ad_player_y_velocity

    # 2. Asteroid Update and Collision Check
    new_asteroids = []
    for ast in ad_asteroids:
        ast.update()

        if ast.check_collision(ad_player_rect):
            game_over()
            return

        # Check if asteroid has passed the player's fixed X position
        if ast.x + ast.size < AD_PLAYER_X - GAME_X and not ast.passed: 
            score += 1
            ast.passed = True

        if ast.x + ast.size > 0:
            new_asteroids.append(ast)
    
    ad_asteroids = new_asteroids

    # 3. Boundary Check
    if ad_player_rect.top < GAME_Y:
        ad_player_rect.top = GAME_Y
        ad_player_y_velocity = 0
    elif ad_player_rect.bottom > GAME_Y + GAME_HEIGHT:
        ad_player_rect.bottom = GAME_Y + GAME_HEIGHT
        ad_player_y_velocity = 0

# --- Drawing Functions (Universal) ---

def draw_gradient_background(surface):
    h = surface.get_height()
    for i in range(h):
        r = GRADIENT_TOP[0] + (GRADIENT_BOTTOM[0] - GRADIENT_TOP[0]) * i / h
        g = GRADIENT_TOP[1] + (GRADIENT_BOTTOM[1] - GRADIENT_TOP[1]) * i / h
        b = GRADIENT_TOP[2] + (GRADIENT_BOTTOM[2] - GRADIENT_TOP[2]) * i / h
        pygame.draw.line(surface, (r, g, b), (0, i), (SCREEN_WIDTH, i))

# Helper for drawing text with outline/shadow for fancy effect
def draw_outlined_text(surface, text, font, color, outline_color, x, y, outline_thickness=2):
    # Draw outline/shadow first
    for dx, dy in [(-outline_thickness, 0), (outline_thickness, 0), (0, -outline_thickness), (0, outline_thickness)]:
        outline_surface = font.render(text, True, outline_color)
        surface.blit(outline_surface, (x + dx, y + dy))
    
    # Draw main text
    main_surface = font.render(text, True, color)
    surface.blit(main_surface, (x, y))

def draw_hud():
    game_title = f"GAME: {'GRAVITY SWITCH' if current_game == 'GRAVITY_SWITCH' else 'ASTEROID DODGE'}"
    
    # 1. Main Title with Shadow (Super Fancy)
    draw_outlined_text(
        screen, game_title, font_title, NEON_CYAN, SHADOW_COLOR, 
        GAME_X, GAME_Y - 40, outline_thickness=3
    )

    # Draw Score 
    score_label = font_medium.render("SCORE:", True, GRAY)
    score_value = font_large.render(str(score), True, NEON_YELLOW)
    screen.blit(score_label, (GAME_X, GAME_Y + GAME_HEIGHT + 10))
    screen.blit(score_value, (GAME_X + score_label.get_width() + 10, GAME_Y + GAME_HEIGHT + 5))
    
    # Draw Player Name (with slight outline for appeal)
    player_text = f"PLAYER: {player_name}"
    draw_outlined_text(
        screen, player_text, font_medium, WHITE, BLACK, 
        GAME_X + GAME_WIDTH - font_medium.size(player_text)[0], GAME_Y + GAME_HEIGHT + 10, outline_thickness=1
    )

def get_back_button_rect():
    """Calculates the rectangle for the back button (top right)."""
    button_w = 180
    button_h = 40
    button_x = SCOREBOARD_X # Aligned with scoreboard
    button_y = 10 # Adjusted for top padding
    return pygame.Rect(button_x, button_y, button_w, button_h)

def draw_back_to_menu_button():
    """Draws a dedicated button to return to the main menu with a glow effect."""
    menu_btn_rect = get_back_button_rect()
    
    if game_state == 'RUNNING' or game_state == 'GAME_OVER':
        # Glowing border effect
        inner_color = RED if game_state == 'RUNNING' else PURPLE
        outer_color = NEON_RED if game_state == 'RUNNING' else (255, 100, 255) 
        
        # Draw soft outer glow
        pygame.draw.rect(screen, outer_color, menu_btn_rect, 5, 8)
        # Draw solid inner fill
        pygame.draw.rect(screen, inner_color, menu_btn_rect, 0, 8)
        # Draw sharp white border
        pygame.draw.rect(screen, WHITE, menu_btn_rect, 2, 8)
        
        draw_outlined_text(
            screen, "<< MAIN MENU", font_small, WHITE, BLACK,
            menu_btn_rect.x + (menu_btn_rect.width - font_small.size("<< MAIN MENU")[0]) // 2, 
            menu_btn_rect.y + 10, outline_thickness=1
        )
    
    return menu_btn_rect 

def draw_scoreboard():
    """Draws the persistent high score board on the right side with a cyber frame."""
    board_rect = pygame.Rect(SCOREBOARD_X, SCOREBOARD_Y, SCOREBOARD_WIDTH, GAME_HEIGHT)
    pygame.draw.rect(screen, DARK_BLUE, board_rect, border_radius=10)
    
    # Fancy Border (multiple layers)
    pygame.draw.rect(screen, CYAN, board_rect, 5, border_radius=10)
    pygame.draw.rect(screen, DARK_BLUE, board_rect, 1, border_radius=10)

    title = font_medium.render("TOP SCORES", True, NEON_CYAN)
    screen.blit(title, (SCOREBOARD_X + SCOREBOARD_WIDTH // 2 - title.get_width() // 2, SCOREBOARD_Y + 15))

    y_offset = SCOREBOARD_Y + 60
    
    pygame.draw.line(screen, GRAY, (SCOREBOARD_X + 10, y_offset), (SCOREBOARD_X + SCOREBOARD_WIDTH - 10, y_offset), 1)
    y_offset += 10
    
    if not active_scores:
        no_scores = font_small.render("No scores yet. Be the first!", True, GRAY)
        screen.blit(no_scores, (SCOREBOARD_X + SCOREBOARD_WIDTH // 2 - no_scores.get_width() // 2, y_offset + 20))

    for i, entry in enumerate(active_scores):
        game_initial = "GS" if entry['game'] == 'GRAVITY_SWITCH' else "AD"
        name_text = font_small.render(f"{i+1}. {entry['name']} ({game_initial})", True, WHITE)
        score_text = font_small.render(str(entry['score']), True, NEON_YELLOW)

        screen.blit(name_text, (SCOREBOARD_X + 20, y_offset + 15))
        screen.blit(score_text, (SCOREBOARD_X + SCOREBOARD_WIDTH - 20 - score_text.get_width(), y_offset + 15))
        y_offset += 30

def draw_webcam_view(image):
    # Convert OpenCV image to Pygame surface
    image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
    image = pygame.image.frombuffer(image.tobytes(), image.shape[1::-1], "RGB")
    image = pygame.transform.scale(image, (WEBCAM_WIDTH, WEBCAM_HEIGHT))
    
    # Draw webcam frame
    webcam_rect = image.get_rect(center=(WEBCAM_DISPLAY_X, WEBCAM_DISPLAY_Y))
    screen.blit(image, webcam_rect)

    # Draw neon border around webcam view
    pygame.draw.rect(screen, NEON_CYAN, webcam_rect, 4, border_radius=5)
    
    # Render status below the webcam view
    instruction_text = "Hand Gesture Control"
    
    if current_game == 'GRAVITY_SWITCH':
        action = "LIFT/THRUST"
    elif current_game == 'ASTEROID_DODGE':
        action = "BOOST/ACCEL"
    else:
        action = "ACTION"
    
    status_text = f"Status: {'OPEN (' + action + ')' if is_hand_open else 'CLOSED (FALL/DRIFT)'}"
    status_color = NEON_CYAN if is_hand_open else NEON_YELLOW
    
    # Draw status text with outline
    draw_outlined_text(
        screen, status_text, font_medium, status_color, BLACK,
        WEBCAM_DISPLAY_X - font_medium.size(status_text)[0] // 2, 
        WEBCAM_DISPLAY_Y + WEBCAM_HEIGHT // 2 + 40, outline_thickness=1
    )
    
    instruction_surface = font_small.render(instruction_text, True, GRAY)
    screen.blit(instruction_surface, (WEBCAM_DISPLAY_X - instruction_surface.get_width() // 2, WEBCAM_DISPLAY_Y + WEBCAM_HEIGHT // 2 + 10))


def draw_game_over_screen():
    # Draw semi-transparent overlay
    overlay = pygame.Surface((GAME_WIDTH, GAME_HEIGHT), pygame.SRCALPHA)
    overlay.fill((30, 30, 50, 200)) 
    screen.blit(overlay, (GAME_X, GAME_Y))

    # Game Over Text
    draw_outlined_text(
        screen, "EXERCISE COMPLETE", font_large, NEON_RED, BLACK,
        GAME_X + GAME_WIDTH // 2 - font_large.size("EXERCISE COMPLETE")[0] // 2, 
        GAME_Y + GAME_HEIGHT // 3, outline_thickness=3
    )

    score_text = f"Final Score: {score}"
    draw_outlined_text(
        screen, score_text, font_medium, WHITE, BLACK,
        GAME_X + GAME_WIDTH // 2 - font_medium.size(score_text)[0] // 2, 
        GAME_Y + GAME_HEIGHT // 3 + 50, outline_thickness=1
    )

    # Restart button text (Clicking game area restarts)
    restart_text = "CLICK GAME AREA TO RESTART"
    draw_outlined_text(
        screen, restart_text, font_medium, NEON_YELLOW, DARK_BLUE,
        GAME_X + GAME_WIDTH // 2 - font_medium.size(restart_text)[0] // 2, 
        GAME_Y + GAME_HEIGHT * 2 // 3, outline_thickness=2
    )


# --- Drawing Functions (Gravity Switch Specific) ---

def draw_gravity_switch_area():
    # 1. Draw Game Background and Grid Overlay
    pygame.draw.rect(screen, DARK_BLUE, (GAME_X, GAME_Y, GAME_WIDTH, GAME_HEIGHT))
    
    # Draw fancy grid lines
    grid_color = (30, 30, 70)
    for i in range(0, GAME_WIDTH, 40):
        x = GAME_X + i
        pygame.draw.line(screen, grid_color, (x, GAME_Y), (x, GAME_Y + GAME_HEIGHT), 1)
    for i in range(0, GAME_HEIGHT, 40):
        y = GAME_Y + i
        pygame.draw.line(screen, grid_color, (GAME_X, y), (GAME_X + GAME_WIDTH, y), 1)

    # 2. Draw Obstacles
    for obs in gs_obstacles:
        obs.draw(screen)
        
    # 3. Draw Player Trail/Glow (using squares/dots for "digital trail")
    player_color = NEON_CYAN if is_hand_open else NEON_YELLOW
    
    for i, pos in enumerate(gs_player_trail):
        alpha = int(255 * (i / len(gs_player_trail)) * 0.5) 
        size = 8 # Fixed trail dot size
        trail_color = player_color + (alpha,)
        
        trail_surface = pygame.Surface((size * 2, size * 2), pygame.SRCALPHA)
        pygame.draw.circle(trail_surface, trail_color, (size, size), size // 2)
        
        trail_rect = trail_surface.get_rect(center=pos)
        screen.blit(trail_surface, trail_rect)
        
    # 4. Draw Player (ball) with Pulse Effect
    pulse_intensity = 1 + math.sin(time.time() * 10) * 0.5 # Pulses between 0.5 and 1.5
    outer_radius = int(GS_PLAYER_SIZE // 2 * pulse_intensity)
    
    pygame.draw.circle(screen, player_color, gs_player_rect.center, outer_radius, 3) # Glow
    pygame.draw.circle(screen, WHITE, gs_player_rect.center, GS_PLAYER_SIZE // 2) # Core

    # 5. Draw Game Border (last, so it overlaps everything)
    pygame.draw.rect(screen, NEON_CYAN, (GAME_X - 5, GAME_Y - 5, GAME_WIDTH + 10, GAME_HEIGHT + 10), 5, border_radius=10)

# --- Drawing Functions (Asteroid Dodge Specific) ---

def draw_asteroid_dodge_area():
    global stars
    
    # 1. Draw Space Background and Scrolling Stars
    pygame.draw.rect(screen, BLACK, (GAME_X, GAME_Y, GAME_WIDTH, GAME_HEIGHT))
    
    # Update and Draw Scrolling Stars
    for i in range(len(stars)):
        x, y, speed = stars[i]
        
        # Move star left (simulating motion)
        x -= speed
        
        # Wrap star around when it leaves the screen
        if x < 0:
            x = GAME_WIDTH
            y = random.randint(0, GAME_HEIGHT)
            speed = random.randint(1, 3)
            
        stars[i][0] = x
        
        star_color = WHITE if speed == 1 else NEON_YELLOW if speed == 2 else NEON_CYAN
        pygame.draw.circle(screen, star_color, (GAME_X + x, GAME_Y + y), 1)
        

    # 2. Draw Asteroids
    for ast in ad_asteroids:
        ast.draw(screen)

    # 3. Draw Player (Spaceship/Triangle) with trail
    player_color = NEON_YELLOW if is_hand_open else NEON_RED
    
    # Define the triangle points (pointing right)
    x, y, size = ad_player_rect.x, ad_player_rect.y, AD_PLAYER_SIZE
    ship_points = [
        (x + size, y + size // 2), # Nose
        (x, y + size),  # Bottom Fin
        (x, y) # Top Fin
    ]
    
    # Draw Ship Trail (simple line effect)
    if is_hand_open:
        trail_end_x = x - 5
        trail_end_y = y + size // 2
        
        pygame.draw.line(screen, NEON_CYAN, (x, y + size // 2), (trail_end_x, trail_end_y), 5)
    
    pygame.draw.polygon(screen, player_color, ship_points)
    pygame.draw.polygon(screen, WHITE, ship_points, 2)
    
    # Draw Thruster effect if hand is open (more pronounced)
    if is_hand_open:
        thruster_points = [
            (x, y + size // 4),
            (x - 25, y + size // 2),
            (x, y + size * 3 // 4)
        ]
        pygame.draw.polygon(screen, NEON_CYAN, thruster_points)

    # 4. Draw Game Border
    pygame.draw.rect(screen, NEON_YELLOW, (GAME_X - 5, GAME_Y - 5, GAME_WIDTH + 10, GAME_HEIGHT + 10), 5, border_radius=10)

# --- Menu Helper Functions ---
def draw_game_preview_box(x, y, size, title, color):
    """Draws a simplified, static preview of a game with a glowing border."""
    
    rect = pygame.Rect(x, y, size, size)
    
    # Draw soft outer glow
    pygame.draw.rect(screen, color, rect, 5, 10)
    # Draw solid inner fill
    pygame.draw.rect(screen, DARK_BLUE, rect, 0, 10) 
    # Draw sharp white border
    pygame.draw.rect(screen, WHITE, rect, 1, 10) 
    
    # Draw simplified game representation inside the box
    if "Gravity Switch" in title:
        # Draw placeholder obstacles
        pygame.draw.rect(screen, LIGHT_BLUE, (x + size * 0.7, y, 20, size * 0.35))
        pygame.draw.rect(screen, LIGHT_BLUE, (x + size * 0.7, y + size * 0.65, 20, size * 0.35))
        # Draw placeholder ball with glow
        pygame.draw.circle(screen, YELLOW, (x + size * 0.3, y + size // 2), 15, 3)
        pygame.draw.circle(screen, WHITE, (x + size * 0.3, y + size // 2), 10)
        
    elif "Asteroid Dodge" in title:
        # Draw stars
        for i in range(15):
            star_x = random.randint(x, x + size)
            star_y = random.randint(y, y + size)
            pygame.draw.circle(screen, NEON_CYAN, (star_x, star_y), 1)
        # Draw placeholder asteroid (large)
        pygame.draw.circle(screen, GRAY, (x + size * 0.8, y + size * 0.2), 35)
        pygame.draw.circle(screen, NEON_RED, (x + size * 0.8, y + size * 0.2), 40, 2) # Red outline
        # Draw placeholder spaceship
        pygame.draw.polygon(screen, PURPLE, [
            (x + size * 0.2 + 20, y + size * 0.5), 
            (x + size * 0.2, y + size * 0.5 + 15), 
            (x + size * 0.2, y + size * 0.5 - 15)
        ])
        
    # Draw Title below the box
    draw_outlined_text(
        screen, title, font_small, color, BLACK,
        x + size // 2 - font_small.size(title)[0] // 2, 
        y + size + 10, outline_thickness=1
    )


def draw_static_hand(center_x, center_y, is_open, color, text):
    """Function to draw a simplified hand demonstration with glowing effect."""
    hand_size = 30
    
    # Draw soft outer glow
    pygame.draw.circle(screen, color, (center_x, center_y), hand_size + 3, 3) 
    
    # Palm (base)
    pygame.draw.circle(screen, DARK_BLUE, (center_x, center_y), hand_size, 0)
    pygame.draw.circle(screen, color, (center_x, center_y), hand_size - 5, 2)
    
    # Fingers
    if is_open: # OPEN (Action)
        pygame.draw.line(screen, color, (center_x, center_y - hand_size), (center_x, center_y - hand_size * 1.5), 5) 
        pygame.draw.line(screen, color, (center_x + hand_size * 0.6, center_y - hand_size * 0.7), (center_x + hand_size * 1.2, center_y - hand_size * 1.2), 5)
        pygame.draw.line(screen, color, (center_x - hand_size * 0.6, center_y - hand_size * 0.7), (center_x - hand_size * 1.2, center_y - hand_size * 1.2), 5)
    else: # CLOSED (Default/Fall)
        pygame.draw.arc(screen, color, (center_x - 30, center_y - 50, 60, 60), math.radians(200), math.radians(340), 5)
        pygame.draw.circle(screen, color, (center_x - 10, center_y - 15), 5)
        pygame.draw.circle(screen, color, (center_x + 10, center_y - 15), 5)

    # Draw Instruction Text
    draw_outlined_text(
        screen, text, font_small, WHITE, BLACK,
        center_x - font_small.size(text)[0] // 2, 
        center_y + hand_size + 10, outline_thickness=1
    )


def draw_main_menu():
    """Draws the main menu with game selection and player name input, and fills space with instructions and previews."""
    global input_box_color, player_name, text_active
    
    # 1. Draw Transparent Overlay over screen
    overlay = pygame.Surface((SCREEN_WIDTH, SCREEN_HEIGHT), pygame.SRCALPHA)
    overlay.fill((10, 10, 30, 240)) 
    screen.blit(overlay, (0, 0))

    # 2. Main Title (with shadow effect)
    main_title = "REHAB GAMES: SELECT EXERCISE"
    draw_outlined_text(
        screen, main_title, font_title, NEON_CYAN, SHADOW_COLOR, 
        SCREEN_WIDTH // 2 - font_title.size(main_title)[0] // 2, 30, outline_thickness=3
    )
    
    # --- Player Name Input (Center Top) ---
    label_w = font_medium.size("PLAYER NAME:")[0]
    box_w = input_box_rect.width
    input_block_w = label_w + 10 + box_w
    
    input_x = (SCREEN_WIDTH // 2) - (input_block_w // 2)
    input_y = 100 # Vertical position of the input box
    
    # 1. Name Label
    draw_outlined_text(
        screen, "PLAYER NAME:", font_medium, WHITE, BLACK,
        input_x, input_y + 5, outline_thickness=1
    )
    
    # 2. Input Box (Fancy)
    input_box_rect.x = input_x + label_w + 10 
    input_box_rect.y = input_y 
    
    name_to_render = player_name if player_name else " "
    txt_surface = font_medium.render(name_to_render, True, WHITE)
    
    pygame.draw.rect(screen, INPUT_BOX_COLOR, input_box_rect, 0, 5) 
    pygame.draw.rect(screen, input_box_color, input_box_rect, 3, 5) # Thicker, colored border
    screen.blit(txt_surface, (input_box_rect.x + 5, input_box_rect.y + 5))
    
    # Highlight warning if text box is active
    if text_active:
        warning_text = "Press ENTER to confirm name."
        draw_outlined_text(
            screen, warning_text, font_small, NEON_YELLOW, BLACK,
            input_x + input_block_w // 2 - font_small.size(warning_text)[0] // 2, 
            input_y + 50, outline_thickness=1
        )


    # --- Motivational Objective Statement ---
    objective_text = "Objective: Improve wrist strength and control through repetitive motion tasks."
    draw_outlined_text(
        screen, objective_text, font_medium, LIGHT_BLUE, DARK_BLUE,
        SCREEN_WIDTH // 2 - font_medium.size(objective_text)[0] // 2, 
        input_y + 120, outline_thickness=1
    )
    
    
    # --- Hand Gesture Instructions (Center) ---
    inst_area_h = 200
    inst_area_y = input_y + 170
    inst_area_rect = pygame.Rect(SCREEN_WIDTH // 2 - 250, inst_area_y, 500, inst_area_h)
    
    pygame.draw.rect(screen, (20, 20, 40, 180), inst_area_rect, 0, 10)
    pygame.draw.rect(screen, NEON_CYAN, inst_area_rect, 3, 10) # Neon frame
    
    inst_title = "GESTURE CONTROL"
    draw_outlined_text(
        screen, inst_title, font_medium, WHITE, BLACK,
        SCREEN_WIDTH // 2 - font_medium.size(inst_title)[0] // 2, 
        inst_area_y + 10, outline_thickness=1
    )
    
    hand_y = inst_area_y + 100
    
    # Left Hand Instruction: CLOSED
    closed_hand_x = SCREEN_WIDTH // 2 - 100
    draw_static_hand(closed_hand_x, hand_y, False, NEON_YELLOW, "CLOSED (DEFAULT/FALL)")
    
    # Right Hand Instruction: OPEN
    open_hand_x = SCREEN_WIDTH // 2 + 100
    draw_static_hand(open_hand_x, hand_y, True, NEON_CYAN, "OPEN (LIFT/THRUST)")


    # --- Game Selection Buttons (Center Middle/Bottom) ---
    
    button_w = 350 
    button_h = 80
    button_gap = 30
    
    # Position buttons lower in the screen
    center_y_buttons = SCREEN_HEIGHT - 250
    center_x = SCREEN_WIDTH // 2
    
    # 1. Gravity Switch Button (Neon Blue)
    gs_btn_rect = pygame.Rect(center_x - button_w // 2, center_y_buttons, button_w, button_h)
    pygame.draw.rect(screen, LIGHT_BLUE, gs_btn_rect, 0, 10)
    pygame.draw.rect(screen, NEON_CYAN, gs_btn_rect, 5, 10)
    
    gs_text = "GRAVITY SWITCH"
    gs_desc = "Control ball with OPEN/CLOSED hand"
    
    draw_outlined_text(screen, gs_text, font_large, BLACK, WHITE,
        gs_btn_rect.x + (button_w - font_large.size(gs_text)[0]) // 2, gs_btn_rect.y + 10, outline_thickness=1)
    draw_outlined_text(screen, gs_desc, font_small, DARK_BLUE, WHITE,
        gs_btn_rect.x + (button_w - font_small.size(gs_desc)[0]) // 2, gs_btn_rect.y + 45, outline_thickness=1)

    
    # 2. Asteroid Dodge Button (Neon Purple/Red)
    ad_btn_rect = pygame.Rect(center_x - button_w // 2, center_y_buttons + button_h + button_gap, button_w, button_h)
    pygame.draw.rect(screen, PURPLE, ad_btn_rect, 0, 10)
    pygame.draw.rect(screen, NEON_YELLOW, ad_btn_rect, 5, 10)
    
    ad_text = "ASTEROID DODGE"
    ad_desc = "Thrust spaceship with OPEN/CLOSED hand"
    
    draw_outlined_text(screen, ad_text, font_large, BLACK, WHITE,
        ad_btn_rect.x + (button_w - font_large.size(ad_text)[0]) // 2, ad_btn_rect.y + 10, outline_thickness=1)
    draw_outlined_text(screen, ad_desc, font_small, DARK_BLUE, WHITE,
        ad_btn_rect.x + (button_w - font_small.size(ad_desc)[0]) // 2, ad_btn_rect.y + 45, outline_thickness=1)


    # --- LEFT SIDE GAME PREVIEWS (NEW) ---
    PREVIEW_SIZE = 250
    PREVIEW_X = 50 
    
    # 1. Gravity Switch Preview (Aligned with Objective/Gesture Block)
    GS_PREVIEW_Y = input_y + 150 
    draw_game_preview_box(PREVIEW_X, GS_PREVIEW_Y, PREVIEW_SIZE, "Gravity Switch", NEON_CYAN)
    
    # 2. Asteroid Dodge Preview (Aligned with Game Selection Buttons)
    AD_PREVIEW_Y = center_y_buttons - 50 
    draw_game_preview_box(PREVIEW_X, AD_PREVIEW_Y, PREVIEW_SIZE, "Asteroid Dodge", PURPLE)
    
    
    return gs_btn_rect, ad_btn_rect

# --- Hand Detection Function ---

def detect_gesture(landmarks):
    """
    Checks for an open hand gesture using landmark distance ratio.
    """
    global is_hand_open

    # Calculate Euclidean distance between points
    def euclidean_distance(p1, p2):
        return math.sqrt((p1.x - p2.x)**2 + (p1.y - p2.y)**2)
    
    # Get landmarks (normalized coordinates 0 to 1)
    wrist = landmarks[mp_hands.HandLandmark.WRIST.value]
    index_tip = landmarks[mp_hands.HandLandmark.INDEX_FINGER_TIP.value]
    pinky_base = landmarks[mp_hands.HandLandmark.PINKY_MCP.value]

    dist_spread = euclidean_distance(index_tip, pinky_base)
    dist_ref = euclidean_distance(wrist, pinky_base)
    
    if dist_ref == 0:
        is_hand_open = False
        return

    HAND_OPEN_THRESHOLD = 1.05 
    open_ratio = dist_spread / dist_ref
    

    if open_ratio > HAND_OPEN_THRESHOLD:
        is_hand_open = True
    else:
        is_hand_open = False

# --- Main Game Loop ---

def main():
    global cap, game_running, is_hand_open, game_state, player_name, text_active, input_box_color, current_game

    # Try opening the webcam
    try:
        cap = cv2.VideoCapture(0)
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, WEBCAM_WIDTH)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, WEBCAM_HEIGHT)

        if not cap.isOpened():
            raise IOError("Cannot open webcam.")
    except Exception as e:
        print(f"Error opening webcam: {e}")
        print("Running in Mouse/Click Fallback Mode.")
        cap = None 
    
    
    while True:
        
        # Check back button outside of the primary event loop handler, but before drawing
        menu_btn_rect = get_back_button_rect()

        # --- Event Handling ---
        for event in pygame.event.get():
            if event.type == pygame.QUIT or (event.type == pygame.KEYDOWN and event.key == pygame.K_ESCAPE):
                if cap is not None:
                    cap.release()
                pygame.quit()
                sys.exit()
            
            if event.type == pygame.MOUSEBUTTONDOWN:
                
                # 1. Handle Back Button Click (High Priority)
                if (game_state == 'RUNNING' or game_state == 'GAME_OVER') and menu_btn_rect.collidepoint(event.pos):
                    game_state = 'MAIN_MENU'
                    game_running = False
                    current_game = None 
                    continue # Skip other mouse handling

                # 2. Handle Game State Clicks
                if game_state == 'MAIN_MENU':
                    # Recalculate input box position for collision check (must match draw_main_menu)
                    label_w = font_medium.size("PLAYER NAME:")[0]
                    input_block_w = label_w + 10 + input_box_rect.width
                    input_x = (SCREEN_WIDTH // 2) - (input_block_w // 2)
                    input_y = 100 # Matches draw_main_menu adjustment
                    input_box_rect.x = input_x + label_w + 10 
                    input_box_rect.y = input_y 
                    
                    # Recalculate menu buttons for collision check
                    button_w, button_h, button_gap = 350, 80, 30 # Updated menu dimensions
                    total_block_h = 2 * button_h + button_gap
                    center_y = SCREEN_HEIGHT - 250 # Updated menu position
                    center_x = SCREEN_WIDTH // 2
                    gs_btn_rect = pygame.Rect(center_x - button_w // 2, center_y, button_w, button_h)
                    ad_btn_rect = pygame.Rect(center_x - button_w // 2, center_y + button_h + button_gap, button_w, button_h)

                    
                    if input_box_rect.collidepoint(event.pos):
                        text_active = True
                        input_box_color = color_active
                    else:
                        text_active = False
                        input_box_color = color_inactive
                    
                    # Check game start buttons
                    if player_name.strip():
                        if gs_btn_rect.collidepoint(event.pos):
                            reset_gravity_switch()
                        elif ad_btn_rect.collidepoint(event.pos):
                            reset_asteroid_dodge()


                elif game_state == 'GAME_OVER':
                    # Click anywhere in the game area to restart the current game
                    game_area_rect = pygame.Rect(GAME_X, GAME_Y, GAME_WIDTH, GAME_HEIGHT)
                    if game_area_rect.collidepoint(event.pos):
                        if current_game == 'GRAVITY_SWITCH':
                            reset_gravity_switch()
                        elif current_game == 'ASTEROID_DODGE':
                            reset_asteroid_dodge()
                
                # Mouse/Click fallback control for RUNNING games
                if cap is None and game_running:
                    is_hand_open = True
            
            if event.type == pygame.MOUSEBUTTONUP:
                if cap is None and game_running:
                    is_hand_open = False
            
            # Text Input Handling
            if event.type == pygame.KEYDOWN and text_active:
                if event.key == pygame.K_RETURN:
                    text_active = False
                    input_box_color = color_inactive
                    
                elif event.key == pygame.K_BACKSPACE:
                    player_name = player_name[:-1]
                else:
                    if len(player_name) < 15:
                         player_name += event.unicode

        # --- Webcam Processing ---
        image = None
        if cap is not None:
            success, image = cap.read()
            if success:
                image = cv2.flip(image, 1) 
                image.flags.writeable = False
                image_rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
                
                results = hands.process(image_rgb)
                
                image.flags.writeable = True

                if results.multi_hand_landmarks:
                    for hand_landmarks in results.multi_hand_landmarks:
                        # Draw hand landmarks on the image
                        mp_drawing.draw_landmarks(
                            image, 
                            hand_landmarks, 
                            mp_hands.HAND_CONNECTIONS,
                            mp_drawing.DrawingSpec(color=NEON_YELLOW, thickness=2, circle_radius=2),
                            mp_drawing.DrawingSpec(color=NEON_CYAN, thickness=2, circle_radius=2)
                        )
                        detect_gesture(hand_landmarks.landmark)
                else:
                    is_hand_open = False

        # --- Game Update and Drawing ---
        draw_gradient_background(screen)
        draw_scoreboard()
        draw_back_to_menu_button() # Draw the back button consistently

        
        if image is not None and game_state != 'MAIN_MENU': # Hide webcam on main menu
             draw_webcam_view(image)
        
        if game_state == 'RUNNING' or game_state == 'GAME_OVER':
            
            if current_game == 'GRAVITY_SWITCH':
                update_gravity_switch()
                draw_gravity_switch_area()
            elif current_game == 'ASTEROID_DODGE':
                update_asteroid_dodge()
                draw_asteroid_dodge_area()
            
            draw_hud()
            
            if game_state == 'GAME_OVER':
                draw_game_over_screen()
        
        elif game_state == 'MAIN_MENU':
            draw_main_menu() 

        pygame.display.flip()
        clock.tick(60) 

if __name__ == "__main__":
    main()