package com.openai.lanecommand;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;

public class GameView extends View {
    private static final int MODE_READY = 0;
    private static final int MODE_PLAYING = 1;
    private static final int MODE_GAME_OVER = 2;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rng = new Random();
    private final SharedPreferences prefs;
    private final Typeface bold = Typeface.create("sans", Typeface.BOLD);

    private float W, H, den;
    private float playerX, targetX;
    private float playerY;
    private boolean active = true;
    private long lastFrameNanos = 0L;
    private int mode = MODE_READY;

    private int squad = 20;
    private int gunTier = 1;
    private int score = 0;
    private int highScore = 0;
    private int encounters = 0;
    private int bossKills = 0;
    private float worldScroll = 0f;
    private float eventTimer = 1.6f;
    private float fireTimer = 0f;
    private float flashTimer = 0f;
    private float bannerTimer = 0f;
    private String bannerText = "";
    private float shieldTimer = 0f;
    private float rapidTimer = 0f;
    private float doubleDamageTimer = 0f;
    private float difficulty = 1f;

    private final ArrayList<Bullet> bullets = new ArrayList<>();
    private final ArrayList<EnemyBullet> enemyBullets = new ArrayList<>();
    private final ArrayList<EnemyGroup> enemies = new ArrayList<>();
    private final ArrayList<Gate> gates = new ArrayList<>();
    private final ArrayList<Crate> crates = new ArrayList<>();
    private final ArrayList<Obstacle> obstacles = new ArrayList<>();
    private final ArrayList<Particle> particles = new ArrayList<>();
    private Crate focusedCrate = null;

    private final RectF tempRect = new RectF();
    private final Path tempPath = new Path();

    public GameView(Context context) {
        super(context);
        den = getResources().getDisplayMetrics().density;
        prefs = context.getSharedPreferences("lane_command", Context.MODE_PRIVATE);
        highScore = prefs.getInt("high_score", 0);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setFocusable(true);
    }

    public void resumeGame() {
        active = true;
        lastFrameNanos = 0L;
        postInvalidateOnAnimation();
    }

    public void pauseGame() {
        active = false;
        lastFrameNanos = 0L;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        W = w;
        H = h;
        playerY = H * 0.79f;
        playerX = W * 0.5f;
        targetX = playerX;
        resetRun(false);
    }

    private void resetRun(boolean startImmediately) {
        bullets.clear();
        enemyBullets.clear();
        enemies.clear();
        gates.clear();
        crates.clear();
        obstacles.clear();
        particles.clear();
        focusedCrate = null;

        squad = 20;
        gunTier = 1;
        score = 0;
        encounters = 0;
        bossKills = 0;
        worldScroll = 0f;
        eventTimer = 1.25f;
        fireTimer = 0f;
        flashTimer = 0f;
        bannerTimer = 0f;
        shieldTimer = 0f;
        rapidTimer = 0f;
        doubleDamageTimer = 0f;
        difficulty = 1f;
        playerX = W * 0.5f;
        targetX = playerX;
        mode = startImmediately ? MODE_PLAYING : MODE_READY;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.nanoTime();
        float dt = 0f;
        if (lastFrameNanos != 0L) {
            dt = Math.min(0.033f, (now - lastFrameNanos) / 1_000_000_000f);
        }
        lastFrameNanos = now;

        if (active && mode == MODE_PLAYING && dt > 0f) {
            update(dt);
        }

        drawWorld(canvas);
        drawEntities(canvas);
        drawPlayer(canvas);
        drawHud(canvas);

        if (mode == MODE_READY) drawReady(canvas);
        if (mode == MODE_GAME_OVER) drawGameOver(canvas);

        if (active) postInvalidateOnAnimation();
    }

    private void update(float dt) {
        difficulty = 1f + score / 1800f + bossKills * 0.22f;
        float worldSpeed = (128f + Math.min(85f, difficulty * 9f)) * den;
        worldScroll += worldSpeed * dt;
        flashTimer = Math.max(0f, flashTimer - dt);
        bannerTimer = Math.max(0f, bannerTimer - dt);
        shieldTimer = Math.max(0f, shieldTimer - dt);
        rapidTimer = Math.max(0f, rapidTimer - dt);
        doubleDamageTimer = Math.max(0f, doubleDamageTimer - dt);

        float follow = Math.min(1f, dt * 13f);
        playerX += (targetX - playerX) * follow;
        playerX = clamp(playerX, roadLeft(playerY) + 26f * den, roadRight(playerY) - 26f * den);

        eventTimer -= dt;
        if (eventTimer <= 0f && countBosses() == 0) {
            spawnNextEvent();
            eventTimer = Math.max(2.1f, 3.35f - Math.min(0.85f, difficulty * 0.08f));
        }

        updateFire(dt);
        updateBullets(dt);
        updateEnemyBullets(dt);
        updateEnemies(dt, worldSpeed);
        updateGates(dt, worldSpeed);
        updateCrates(dt, worldSpeed);
        updateObstacles(dt, worldSpeed);
        updateParticles(dt);

        if (squad <= 0) endRun();
    }

    private int countBosses() {
        int c = 0;
        for (EnemyGroup e : enemies) if (e.boss) c++;
        return c;
    }

    private void spawnNextEvent() {
        encounters++;
        if (encounters % 9 == 0) {
            spawnBoss();
            return;
        }
        int pattern = encounters % 5;
        if (pattern == 1) {
            spawnGatePair(encounters < 3);
        } else if (pattern == 2) {
            spawnEnemyWave(false);
        } else if (pattern == 3) {
            spawnCrateChoice();
        } else if (pattern == 4) {
            spawnEnemyWave(false);
            if (encounters > 5 && rng.nextFloat() < 0.42f) spawnObstacle();
        } else {
            spawnGatePair(false);
        }
    }

    private void spawnGatePair(boolean generous) {
        float y = -85f * den;
        float l = roadLeft(y);
        float r = roadRight(y);
        float mid = (l + r) * 0.5f;
        float gap = 4f * den;

        Gate a;
        Gate b;
        if (generous || encounters <= 2) {
            a = new Gate(l + gap, mid - gap, y, "x2", 2, true, Gate.TYPE_MULTIPLY);
            b = new Gate(mid + gap, r - gap, y, "+20", 20, true, Gate.TYPE_ADD);
        } else {
            if (rng.nextFloat() < 0.7f) {
                int add = 10 + rng.nextInt(21);
                a = new Gate(l + gap, mid - gap, y, "+" + add, add, true, Gate.TYPE_ADD);
                if (rng.nextFloat() < 0.55f) {
                    b = new Gate(mid + gap, r - gap, y, "x2", 2, true, Gate.TYPE_MULTIPLY);
                } else {
                    int add2 = 15 + rng.nextInt(26);
                    b = new Gate(mid + gap, r - gap, y, "+" + add2, add2, true, Gate.TYPE_ADD);
                }
            } else {
                int loss = 1 + rng.nextInt(7);
                a = new Gate(l + gap, mid - gap, y, "-" + loss, loss, false, Gate.TYPE_SUBTRACT);
                int add = 18 + rng.nextInt(28);
                b = new Gate(mid + gap, r - gap, y, "+" + add, add, true, Gate.TYPE_ADD);
            }
        }
        gates.add(a);
        gates.add(b);
    }

    private void spawnEnemyWave(boolean bossMinions) {
        int count = bossMinions ? 10 + rng.nextInt(10)
                : Math.min(80, 9 + (int)(difficulty * 5f) + rng.nextInt(10));
        float y = -100f * den;
        float lane = rng.nextFloat();
        float x = roadLeft(y) + roadWidth(y) * (0.22f + lane * 0.56f);
        enemies.add(new EnemyGroup(x, y, count, false));
    }

    private void spawnBoss() {
        int hp = 60 + (int)(difficulty * 28f) + bossKills * 20;
        enemies.add(new EnemyGroup(W * 0.5f, -140f * den, hp, true));
        showBanner("BOSS INCOMING");
    }

    private void spawnCrateChoice() {
        float y = -85f * den;
        float leftX = roadLeft(y) + roadWidth(y) * 0.33f;
        float rightX = roadLeft(y) + roadWidth(y) * 0.67f;
        int hp1 = Math.max(8, 9 + (int)(difficulty * 2.2f) + rng.nextInt(8));
        int hp2 = Math.max(12, 14 + (int)(difficulty * 3.2f) + rng.nextInt(12));

        Crate c1 = new Crate(leftX, y, hp1, chooseCrateReward(false));
        Crate c2 = new Crate(rightX, y, hp2, chooseCrateReward(true));
        crates.add(c1);
        crates.add(c2);
    }

    private Reward chooseCrateReward(boolean stronger) {
        float r = rng.nextFloat();
        if (stronger && r < 0.20f) return new Reward("x2", Reward.MULTIPLY, 2);
        if (r < 0.38f) return new Reward("+10", Reward.ADD, 10);
        if (r < 0.58f) return new Reward("+5", Reward.ADD, 5);
        if (r < 0.75f) return new Reward("GUN+", Reward.GUN, 1);
        if (r < 0.88f) return new Reward("RAPID", Reward.RAPID, 8);
        return new Reward("2x DMG", Reward.DOUBLE_DAMAGE, 8);
    }

    private void spawnObstacle() {
        float y = -90f * den;
        boolean left = rng.nextBoolean();
        float l = roadLeft(y);
        float r = roadRight(y);
        float w = roadWidth(y) * 0.38f;
        float x = left ? l + roadWidth(y) * 0.27f : r - roadWidth(y) * 0.27f;
        obstacles.add(new Obstacle(x, y, w, 45f * den));
    }

    private void updateFire(float dt) {
        fireTimer -= dt;
        if (fireTimer > 0f) return;

        float base = 0.095f - Math.min(0.035f, (gunTier - 1) * 0.006f);
        if (rapidTimer > 0f) base *= 0.55f;
        fireTimer = Math.max(0.035f, base);

        if (bullets.size() >= 220) return;

        int streams = Math.min(14, 3 + squad / 7 + gunTier);
        float formationW = Math.min(170f * den, 42f * den + squad * 1.35f * den);
        for (int i = 0; i < streams && bullets.size() < 220; i++) {
            float t = streams == 1 ? 0.5f : i / (float)(streams - 1);
            float x = playerX - formationW * 0.5f + t * formationW;
            float jitter = (rng.nextFloat() - 0.5f) * 7f * den;
            float dmg = 1f + (gunTier - 1) * 0.38f;
            if (doubleDamageTimer > 0f) dmg *= 2f;
            bullets.add(new Bullet(x + jitter, playerY - 45f * den,
                    (rng.nextFloat() - 0.5f) * 18f * den,
                    -(620f + gunTier * 36f) * den,
                    dmg));
        }
        flashTimer = 0.04f;
    }

    private void updateBullets(float dt) {
        for (int i = bullets.size() - 1; i >= 0; i--) {
            Bullet b = bullets.get(i);

            if (focusedCrate != null && !focusedCrate.dead && crates.contains(focusedCrate)) {
                float dx = focusedCrate.x - b.x;
                float dy = focusedCrate.y - b.y;
                float len = (float)Math.sqrt(dx * dx + dy * dy);
                if (len > 1f) {
                    float desiredVx = dx / len * 145f * den;
                    b.vx += (desiredVx - b.vx) * Math.min(1f, dt * 5f);
                }
            }

            b.x += b.vx * dt;
            b.y += b.vy * dt;
            b.life -= dt;
            boolean remove = b.life <= 0f || b.y < -40f * den || b.x < -30f * den || b.x > W + 30f * den;

            if (!remove) {
                for (int j = crates.size() - 1; j >= 0; j--) {
                    Crate c = crates.get(j);
                    if (!c.dead && hit(b.x, b.y, c.x, c.y, c.radius() * den)) {
                        c.hp -= b.damage;
                        addHitParticles(b.x, b.y, true);
                        remove = true;
                        if (c.hp <= 0f) breakCrate(c);
                        break;
                    }
                }
            }

            if (!remove) {
                for (int j = enemies.size() - 1; j >= 0; j--) {
                    EnemyGroup e = enemies.get(j);
                    if (hit(b.x, b.y, e.x, e.y, e.hitRadius() * den)) {
                        e.hp -= b.damage;
                        addHitParticles(b.x, b.y, false);
                        remove = true;
                        if (e.hp <= 0f) killEnemy(e);
                        break;
                    }
                }
            }

            if (!remove) {
                for (int j = obstacles.size() - 1; j >= 0; j--) {
                    Obstacle o = obstacles.get(j);
                    if (Math.abs(b.x - o.x) < o.w * 0.5f && Math.abs(b.y - o.y) < o.h * 0.5f) {
                        addHitParticles(b.x, b.y, false);
                        remove = true;
                        break;
                    }
                }
            }

            if (remove && i < bullets.size()) bullets.remove(i);
        }
    }

    private void updateEnemyBullets(float dt) {
        for (int i = enemyBullets.size() - 1; i >= 0; i--) {
            EnemyBullet b = enemyBullets.get(i);
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            if (b.y > H + 30f * den || b.life <= 0f) {
                enemyBullets.remove(i);
                continue;
            }
            b.life -= dt;
            if (Math.abs(b.y - playerY) < 34f * den && Math.abs(b.x - playerX) < squadRadius()) {
                enemyBullets.remove(i);
                if (shieldTimer <= 0f) {
                    squad -= Math.max(1, b.damage);
                    if (squad < 0) squad = 0;
                    showBanner("-" + Math.max(1, b.damage) + " SOLDIERS");
                } else {
                    addShieldParticles(b.x, b.y);
                }
            }
        }
    }

    private void updateEnemies(float dt, float worldSpeed) {
        for (int i = enemies.size() - 1; i >= 0; i--) {
            EnemyGroup e = enemies.get(i);
            if (e.dead) {
                enemies.remove(i);
                continue;
            }

            if (e.boss) {
                float stopY = H * 0.25f;
                if (e.y < stopY) e.y += worldSpeed * 0.62f * dt;
                else {
                    e.fireTimer -= dt;
                    if (e.fireTimer <= 0f && enemyBullets.size() < 40) {
                        e.fireTimer = Math.max(0.4f, 0.95f - difficulty * 0.04f);
                        int shots = Math.min(5, 2 + bossKills / 2);
                        for (int s = 0; s < shots; s++) {
                            float spread = (s - (shots - 1) / 2f) * 38f * den;
                            enemyBullets.add(new EnemyBullet(e.x + spread * 0.25f, e.y + 38f * den,
                                    spread * 0.55f, 240f * den, 1 + bossKills / 2));
                        }
                    }
                }
            } else {
                e.y += worldSpeed * 0.96f * dt;
            }

            if (!e.boss && e.y >= playerY - 35f * den) {
                int enemyCount = Math.max(1, (int)Math.ceil(e.hp));
                int losses = Math.min(squad, enemyCount);
                squad -= losses;
                e.dead = true;
                addExplosion(e.x, playerY - 10f * den, Color.rgb(255, 105, 70), 22);
                showBanner("CLASH -" + losses);
            }

            if (e.y > H + 120f * den) {
                enemies.remove(i);
            }
        }
    }

    private void updateGates(float dt, float worldSpeed) {
        for (int i = gates.size() - 1; i >= 0; i--) {
            Gate g = gates.get(i);
            g.y += worldSpeed * dt;
            if (!g.used && g.y >= playerY - 18f * den) {
                if (playerX >= g.x1 && playerX <= g.x2) {
                    applyGate(g);
                    g.used = true;
                    addExplosion((g.x1 + g.x2) * 0.5f, g.y, g.positive ? Color.rgb(49, 169, 255) : Color.rgb(255, 76, 73), 28);
                }
            }
            if (g.y > H + 80f * den || g.used) gates.remove(i);
        }
    }

    private void applyGate(Gate g) {
        if (g.type == Gate.TYPE_ADD) {
            squad += g.value;
            showBanner("+" + g.value + " SOLDIERS");
        } else if (g.type == Gate.TYPE_MULTIPLY) {
            squad = Math.min(240, squad * g.value);
            showBanner("x" + g.value + " SQUAD");
        } else {
            squad = Math.max(1, squad - g.value);
            showBanner("-" + g.value + " SOLDIERS");
        }
        squad = Math.min(240, squad);
        score += g.positive ? 80 : 15;
    }

    private void updateCrates(float dt, float worldSpeed) {
        for (int i = crates.size() - 1; i >= 0; i--) {
            Crate c = crates.get(i);
            if (c.dead) {
                if (focusedCrate == c) focusedCrate = null;
                crates.remove(i);
                continue;
            }
            c.y += worldSpeed * dt;
            if (c.y > playerY + 25f * den) {
                if (focusedCrate == c) focusedCrate = null;
                crates.remove(i);
            }
        }
    }

    private void breakCrate(Crate c) {
        c.dead = true;
        applyReward(c.reward);
        addExplosion(c.x, c.y, Color.rgb(255, 180, 64), 34);
        score += 120;
    }

    private void applyReward(Reward r) {
        switch (r.type) {
            case Reward.ADD:
                squad = Math.min(240, squad + r.value);
                showBanner(r.label + " SOLDIERS");
                break;
            case Reward.MULTIPLY:
                squad = Math.min(240, squad * r.value);
                showBanner("x" + r.value + " SQUAD");
                break;
            case Reward.GUN:
                gunTier = Math.min(8, gunTier + 1);
                showBanner("GUN TIER " + gunTier);
                break;
            case Reward.RAPID:
                rapidTimer = Math.max(rapidTimer, r.value);
                showBanner("RAPID FIRE");
                break;
            case Reward.DOUBLE_DAMAGE:
                doubleDamageTimer = Math.max(doubleDamageTimer, r.value);
                showBanner("2x DAMAGE");
                break;
            case Reward.SHIELD:
                shieldTimer = Math.max(shieldTimer, r.value);
                showBanner("SHIELD");
                break;
        }
    }

    private void updateObstacles(float dt, float worldSpeed) {
        for (int i = obstacles.size() - 1; i >= 0; i--) {
            Obstacle o = obstacles.get(i);
            o.y += worldSpeed * dt;
            if (!o.hit && o.y >= playerY - 18f * den) {
                float playerR = squadRadius() * 0.72f;
                if (Math.abs(playerX - o.x) < o.w * 0.5f + playerR) {
                    o.hit = true;
                    int loss = Math.max(3, (int)Math.ceil(squad * 0.18f));
                    if (shieldTimer <= 0f) {
                        squad = Math.max(0, squad - loss);
                        showBanner("SPIKES -" + loss);
                    } else {
                        addShieldParticles(playerX, playerY);
                    }
                    addExplosion(o.x, o.y, Color.rgb(130, 140, 160), 30);
                }
            }
            if (o.y > H + 80f * den) obstacles.remove(i);
        }
    }

    private void updateParticles(float dt) {
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle q = particles.get(i);
            q.life -= dt;
            q.x += q.vx * dt;
            q.y += q.vy * dt;
            q.vy += 180f * den * dt;
            if (q.life <= 0f) particles.remove(i);
        }
        while (particles.size() > 240) particles.remove(0);
    }

    private void killEnemy(EnemyGroup e) {
        if (e.dead) return;
        e.dead = true;
        int reward = e.boss ? 650 : 70 + e.initialCount * 4;
        score += reward;
        if (e.boss) {
            bossKills++;
            squad = Math.min(240, squad + 15);
            gunTier = Math.min(8, gunTier + 1);
            showBanner("BOSS DOWN +15 + GUN");
            addExplosion(e.x, e.y, Color.rgb(255, 108, 55), 70);
        } else {
            showBanner("ENEMY CLEARED");
            addExplosion(e.x, e.y, Color.rgb(239, 87, 77), 28);
        }
    }

    private void endRun() {
        squad = 0;
        mode = MODE_GAME_OVER;
        if (score > highScore) {
            highScore = score;
            prefs.edit().putInt("high_score", highScore).apply();
        }
    }

    private void showBanner(String text) {
        bannerText = text;
        bannerTimer = 1.15f;
    }

    private void addHitParticles(float x, float y, boolean wood) {
        int color = wood ? Color.rgb(238, 171, 76) : Color.rgb(83, 202, 255);
        for (int k = 0; k < 4 && particles.size() < 240; k++) {
            float a = rng.nextFloat() * 6.283f;
            float sp = (55f + rng.nextFloat() * 120f) * den;
            particles.add(new Particle(x, y, (float)Math.cos(a) * sp, (float)Math.sin(a) * sp,
                    0.18f + rng.nextFloat() * 0.20f, color, 2.5f * den));
        }
    }

    private void addShieldParticles(float x, float y) {
        for (int k = 0; k < 16 && particles.size() < 240; k++) {
            float a = k / 16f * 6.283f;
            float sp = 120f * den;
            particles.add(new Particle(x, y, (float)Math.cos(a) * sp, (float)Math.sin(a) * sp,
                    0.4f, Color.rgb(70, 190, 255), 3.5f * den));
        }
    }

    private void addExplosion(float x, float y, int color, int count) {
        int n = Math.min(count, 80);
        for (int k = 0; k < n && particles.size() < 240; k++) {
            float a = rng.nextFloat() * 6.283f;
            float sp = (55f + rng.nextFloat() * 250f) * den;
            particles.add(new Particle(x, y, (float)Math.cos(a) * sp,
                    (float)Math.sin(a) * sp - 35f * den,
                    0.28f + rng.nextFloat() * 0.45f,
                    k % 3 == 0 ? Color.WHITE : color,
                    (2.5f + rng.nextFloat() * 4.5f) * den));
        }
    }

    private void drawWorld(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, 0, 0, H,
                Color.rgb(117, 192, 222), Color.rgb(73, 137, 168), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);

        float roadLTop = roadLeft(0);
        float roadRTop = roadRight(0);
        float roadLBot = roadLeft(H);
        float roadRBot = roadRight(H);

        p.setColor(Color.rgb(213, 184, 137));
        tempPath.reset();
        tempPath.moveTo(0, 0);
        tempPath.lineTo(roadLTop, 0);
        tempPath.lineTo(roadLBot, H);
        tempPath.lineTo(0, H);
        tempPath.close();
        c.drawPath(tempPath, p);
        tempPath.reset();
        tempPath.moveTo(roadRTop, 0);
        tempPath.lineTo(W, 0);
        tempPath.lineTo(W, H);
        tempPath.lineTo(roadRBot, H);
        tempPath.close();
        c.drawPath(tempPath, p);

        p.setShader(new LinearGradient(0, 0, 0, H,
                Color.rgb(92, 91, 88), Color.rgb(69, 72, 76), Shader.TileMode.CLAMP));
        tempPath.reset();
        tempPath.moveTo(roadLTop, 0);
        tempPath.lineTo(roadRTop, 0);
        tempPath.lineTo(roadRBot, H);
        tempPath.lineTo(roadLBot, H);
        tempPath.close();
        c.drawPath(tempPath, p);
        p.setShader(null);

        stroke.setColor(Color.rgb(220, 220, 215));
        stroke.setStrokeWidth(3f * den);
        c.drawLine(roadLTop, 0, roadLBot, H, stroke);
        c.drawLine(roadRTop, 0, roadRBot, H, stroke);

        drawRoadMarks(c);
        drawSideScenery(c);
    }

    private void drawRoadMarks(Canvas c) {
        float period = 86f * den;
        float offset = worldScroll % period;
        p.setColor(Color.argb(195, 245, 243, 228));
        for (float y = -period + offset; y < H + period; y += period) {
            float x = W * 0.5f;
            float sw = 5f * den * perspective(y);
            tempRect.set(x - sw * 0.5f, y, x + sw * 0.5f, y + 34f * den * perspective(y));
            c.drawRoundRect(tempRect, 2f * den, 2f * den, p);
        }

        float crossPeriod = 430f * den;
        float crossOffset = worldScroll % crossPeriod;
        for (float y = -crossPeriod + crossOffset; y < H + crossPeriod; y += crossPeriod) {
            if (y < -70f * den || y > H + 70f * den) continue;
            float l = roadLeft(y);
            float r = roadRight(y);
            int bars = 7;
            float gap = (r - l) / (bars * 2f + 1f);
            p.setColor(Color.argb(190, 241, 236, 219));
            for (int i = 0; i < bars; i++) {
                float x1 = l + gap * (1f + i * 2f);
                tempRect.set(x1, y, x1 + gap, y + 15f * den * perspective(y));
                c.drawRect(tempRect, p);
            }
        }
    }

    private void drawSideScenery(Canvas c) {
        float lampPeriod = 150f * den;
        float off = worldScroll % lampPeriod;
        for (float y = -lampPeriod + off; y < H + lampPeriod; y += lampPeriod) {
            float s = perspective(y);
            drawLamp(c, roadLeft(y) - 20f * den * s, y, s);
            drawLamp(c, roadRight(y) + 20f * den * s, y, s);
        }

        float blockPeriod = 305f * den;
        float blockOff = worldScroll % blockPeriod;
        for (float y = -blockPeriod + blockOff; y < H + blockPeriod; y += blockPeriod) {
            float s = perspective(y);
            drawStore(c, roadLeft(y) - 68f * den * s, y - 28f * den * s, s, true);
            drawStore(c, roadRight(y) + 68f * den * s, y - 28f * den * s, s, false);
        }
    }

    private void drawLamp(Canvas c, float x, float y, float s) {
        stroke.setColor(Color.rgb(54, 75, 76));
        stroke.setStrokeWidth(3f * den * s);
        c.drawLine(x, y, x, y - 28f * den * s, stroke);
        p.setColor(Color.rgb(255, 222, 123));
        c.drawCircle(x, y - 31f * den * s, 5f * den * s, p);
        p.setColor(Color.rgb(70, 96, 94));
        stroke.setColor(Color.rgb(70, 96, 94));
        stroke.setStrokeWidth(2f * den * s);
        c.drawCircle(x, y - 31f * den * s, 6f * den * s, stroke);
    }

    private void drawStore(Canvas c, float x, float y, float s, boolean left) {
        float w = 54f * den * s;
        float h = 48f * den * s;
        p.setColor(left ? Color.rgb(211, 131, 100) : Color.rgb(104, 158, 186));
        tempRect.set(x - w * 0.5f, y - h, x + w * 0.5f, y);
        c.drawRoundRect(tempRect, 4f * den * s, 4f * den * s, p);
        p.setColor(Color.rgb(245, 232, 190));
        tempRect.set(x - w * 0.28f, y - h * 0.65f, x + w * 0.28f, y - h * 0.45f);
        c.drawRoundRect(tempRect, 2f * den * s, 2f * den * s, p);
        p.setColor(Color.rgb(74, 55, 48));
        p.setTypeface(bold);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(7f * den * s);
        c.drawText(left ? "CAFE" : "SHOP", x, y - h * 0.50f, p);
    }

    private void drawEntities(Canvas c) {
        for (Gate g : gates) drawGate(c, g);
        for (Crate q : crates) drawCrate(c, q);
        for (Obstacle o : obstacles) drawObstacle(c, o);
        for (EnemyGroup e : enemies) drawEnemyGroup(c, e);
        for (EnemyBullet b : enemyBullets) drawEnemyBullet(c, b);
        for (Bullet b : bullets) drawBullet(c, b);
        for (Particle q : particles) drawParticle(c, q);
    }

    private void drawGate(Canvas c, Gate g) {
        float s = perspective(g.y);
        float h = 44f * den * s;
        int base = g.positive ? Color.rgb(39, 143, 255) : Color.rgb(232, 62, 67);
        p.setColor(Color.argb(190, Color.red(base), Color.green(base), Color.blue(base)));
        tempRect.set(g.x1, g.y - h * 0.5f, g.x2, g.y + h * 0.5f);
        p.setShadowLayer(12f * den * s, 0, 0, base);
        c.drawRoundRect(tempRect, 5f * den * s, 5f * den * s, p);
        p.clearShadowLayer();

        stroke.setColor(g.positive ? Color.rgb(128, 220, 255) : Color.rgb(255, 143, 137));
        stroke.setStrokeWidth(2.5f * den * s);
        c.drawRoundRect(tempRect, 5f * den * s, 5f * den * s, stroke);

        p.setColor(Color.WHITE);
        p.setTypeface(bold);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(30f * den * s);
        p.setShadowLayer(3f * den * s, 0, 2f * den * s, Color.argb(150, 0, 0, 0));
        c.drawText(g.label, (g.x1 + g.x2) * 0.5f, g.y + 10f * den * s, p);
        p.clearShadowLayer();
    }

    private void drawCrate(Canvas c, Crate q) {
        float s = perspective(q.y);
        float rw = 36f * den * s;
        float rh = 30f * den * s;
        float hpRatio = clamp(q.hp / q.maxHp, 0f, 1f);
        int wood = hpRatio < 0.35f ? Color.rgb(202, 83, 51) : Color.rgb(175, 111, 47);

        p.setColor(Color.argb(55, 0, 0, 0));
        tempRect.set(q.x - rw * 0.65f, q.y + rh * 0.45f, q.x + rw * 0.65f, q.y + rh * 0.75f);
        c.drawOval(tempRect, p);

        p.setColor(wood);
        tempRect.set(q.x - rw, q.y - rh, q.x + rw, q.y + rh);
        c.drawRoundRect(tempRect, 10f * den * s, 10f * den * s, p);
        stroke.setColor(Color.rgb(94, 65, 45));
        stroke.setStrokeWidth(4f * den * s);
        c.drawRoundRect(tempRect, 10f * den * s, 10f * den * s, stroke);
        c.drawLine(q.x - rw * 0.45f, q.y - rh, q.x - rw * 0.45f, q.y + rh, stroke);
        c.drawLine(q.x + rw * 0.45f, q.y - rh, q.x + rw * 0.45f, q.y + rh, stroke);

        p.setTypeface(bold);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(Color.WHITE);
        p.setTextSize(24f * den * s);
        p.setShadowLayer(2f * den * s, 0, 2f * den * s, Color.BLACK);
        c.drawText(String.valueOf(Math.max(0, (int)Math.ceil(q.hp))), q.x, q.y + 8f * den * s, p);
        p.clearShadowLayer();

        p.setColor(Color.rgb(36, 150, 255));
        tempRect.set(q.x - rw * 0.80f, q.y - rh - 18f * den * s, q.x + rw * 0.80f, q.y - rh - 2f * den * s);
        c.drawRoundRect(tempRect, 5f * den * s, 5f * den * s, p);
        p.setColor(Color.WHITE);
        p.setTextSize(10f * den * s);
        c.drawText(q.reward.label, q.x, q.y - rh - 7f * den * s, p);

        if (focusedCrate == q) {
            stroke.setColor(Color.rgb(255, 231, 74));
            stroke.setStrokeWidth(3f * den * s);
            c.drawCircle(q.x, q.y, rw * 1.25f, stroke);
        }
    }

    private void drawObstacle(Canvas c, Obstacle o) {
        float s = perspective(o.y);
        float w = o.w * s;
        float h = o.h * s;
        p.setColor(Color.argb(55, 0, 0, 0));
        tempRect.set(o.x - w * 0.55f, o.y + h * 0.28f, o.x + w * 0.55f, o.y + h * 0.55f);
        c.drawOval(tempRect, p);

        p.setColor(Color.rgb(74, 82, 96));
        tempRect.set(o.x - w * 0.5f, o.y - h * 0.4f, o.x + w * 0.5f, o.y + h * 0.4f);
        c.drawRoundRect(tempRect, 10f * den * s, 10f * den * s, p);
        stroke.setColor(Color.rgb(180, 188, 195));
        stroke.setStrokeWidth(2f * den * s);
        c.drawRoundRect(tempRect, 10f * den * s, 10f * den * s, stroke);

        p.setColor(Color.rgb(195, 202, 208));
        for (int i = -3; i <= 3; i++) {
            float xx = o.x + i * w / 7f;
            tempPath.reset();
            tempPath.moveTo(xx - 5f * den * s, o.y - h * 0.18f);
            tempPath.lineTo(xx, o.y - h * 0.55f);
            tempPath.lineTo(xx + 5f * den * s, o.y - h * 0.18f);
            tempPath.close();
            c.drawPath(tempPath, p);
        }
    }

    private void drawEnemyGroup(Canvas c, EnemyGroup e) {
        float s = perspective(e.y);
        if (e.boss) {
            drawBoss(c, e, s);
            return;
        }

        int visible = Math.min(42, Math.max(1, (int)Math.ceil(e.hp)));
        int cols = Math.max(3, (int)Math.ceil(Math.sqrt(visible * 1.3f)));
        float spacing = 16f * den * s;
        for (int i = 0; i < visible; i++) {
            int row = i / cols;
            int col = i % cols;
            int rowCount = Math.min(cols, visible - row * cols);
            float xx = e.x + (col - (rowCount - 1) * 0.5f) * spacing;
            float yy = e.y + row * 12f * den * s;
            drawSoldier(c, xx, yy, 0.78f * s, false, false);
        }
        drawCountBubble(c, e.x, e.y - 24f * den * s, Math.max(0, (int)Math.ceil(e.hp)), false, s);
    }

    private void drawBoss(Canvas c, EnemyGroup e, float s) {
        float size = 1.25f * s;
        p.setColor(Color.argb(60, 0, 0, 0));
        tempRect.set(e.x - 42f * den * size, e.y + 30f * den * size,
                e.x + 42f * den * size, e.y + 50f * den * size);
        c.drawOval(tempRect, p);

        p.setColor(Color.rgb(68, 74, 82));
        tempRect.set(e.x - 42f * den * size, e.y - 6f * den * size,
                e.x + 42f * den * size, e.y + 36f * den * size);
        c.drawRoundRect(tempRect, 14f * den * size, 14f * den * size, p);
        p.setColor(Color.rgb(205, 60, 55));
        tempRect.set(e.x - 30f * den * size, e.y - 18f * den * size,
                e.x + 30f * den * size, e.y + 20f * den * size);
        c.drawRoundRect(tempRect, 12f * den * size, 12f * den * size, p);

        drawSoldier(c, e.x, e.y - 22f * den * size, 1.15f * size, false, true);

        p.setColor(Color.rgb(44, 50, 58));
        for (int side = -1; side <= 1; side += 2) {
            float bx = e.x + side * 44f * den * size;
            tempRect.set(bx - 16f * den * size, e.y - 4f * den * size,
                    bx + 16f * den * size, e.y + 14f * den * size);
            c.drawRoundRect(tempRect, 5f * den * size, 5f * den * size, p);
            stroke.setColor(Color.rgb(15, 18, 22));
            stroke.setStrokeWidth(4f * den * size);
            for (int k = -1; k <= 1; k++) {
                c.drawLine(bx + side * 10f * den * size, e.y + k * 5f * den * size,
                        bx + side * 28f * den * size, e.y + k * 5f * den * size, stroke);
            }
        }

        float barW = 150f * den * s;
        float ratio = clamp(e.hp / e.maxHp, 0f, 1f);
        p.setColor(Color.rgb(42, 48, 55));
        tempRect.set(e.x - barW * 0.5f, e.y - 76f * den * s,
                e.x + barW * 0.5f, e.y - 61f * den * s);
        c.drawRoundRect(tempRect, 7f * den * s, 7f * den * s, p);
        p.setColor(Color.rgb(238, 62, 61));
        tempRect.set(e.x - barW * 0.5f + 2f * den * s, e.y - 74f * den * s,
                e.x - barW * 0.5f + 2f * den * s + (barW - 4f * den * s) * ratio,
                e.y - 63f * den * s);
        c.drawRoundRect(tempRect, 5f * den * s, 5f * den * s, p);
        p.setColor(Color.WHITE);
        p.setTypeface(bold);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(11f * den * s);
        c.drawText("BOSS  " + Math.max(0, (int)Math.ceil(e.hp)), e.x, e.y - 64f * den * s, p);
    }

    private void drawPlayer(Canvas c) {
        if (W <= 0 || H <= 0) return;
        int visible = Math.min(72, Math.max(1, squad));
        int cols = Math.max(4, Math.min(10, (int)Math.ceil(Math.sqrt(visible * 1.4f))));
        float spacingX = Math.min(22f * den, (roadWidth(playerY) * 0.72f) / Math.max(4, cols));
        float spacingY = 15f * den;

        int rows = (int)Math.ceil(visible / (float)cols);
        for (int row = rows - 1; row >= 0; row--) {
            int start = row * cols;
            int rowCount = Math.min(cols, visible - start);
            for (int j = 0; j < rowCount; j++) {
                int idx = start + j;
                if (idx >= visible) continue;
                float xx = playerX + (j - (rowCount - 1) * 0.5f) * spacingX;
                float yy = playerY + row * spacingY - (rows - 1) * spacingY * 0.35f;
                float scale = 0.72f + row * 0.008f;
                drawSoldier(c, xx, yy, scale, true, false);
            }
        }

        drawCommander(c, playerX, playerY + 12f * den);
        drawCountBubble(c, playerX, playerY - 64f * den, squad, true, 1f);

        if (shieldTimer > 0f) {
            stroke.setColor(Color.argb(170, 77, 196, 255));
            stroke.setStrokeWidth(3f * den);
            c.drawCircle(playerX, playerY + 12f * den, squadRadius() + 14f * den, stroke);
        }
    }

    private void drawSoldier(Canvas c, float x, float y, float scale, boolean blue, boolean bossFace) {
        float s = den * scale;
        int team = blue ? Color.rgb(39, 143, 242) : Color.rgb(224, 63, 61);
        int teamDark = blue ? Color.rgb(23, 88, 167) : Color.rgb(155, 43, 44);

        p.setColor(Color.argb(55, 0, 0, 0));
        tempRect.set(x - 9f * s, y + 10f * s, x + 9f * s, y + 16f * s);
        c.drawOval(tempRect, p);

        p.setColor(Color.rgb(38, 42, 49));
        tempRect.set(x - 7f * s, y + 5f * s, x - 1f * s, y + 14f * s);
        c.drawRoundRect(tempRect, 3f * s, 3f * s, p);
        tempRect.set(x + 1f * s, y + 5f * s, x + 7f * s, y + 14f * s);
        c.drawRoundRect(tempRect, 3f * s, 3f * s, p);

        p.setColor(Color.rgb(239, 216, 176));
        tempRect.set(x - 8f * s, y - 1f * s, x + 8f * s, y + 8f * s);
        c.drawRoundRect(tempRect, 4f * s, 4f * s, p);
        p.setColor(team);
        tempRect.set(x - 8f * s, y + 4f * s, x + 8f * s, y + 10f * s);
        c.drawRoundRect(tempRect, 3f * s, 3f * s, p);

        p.setColor(Color.rgb(242, 206, 165));
        c.drawCircle(x, y - 8f * s, 8f * s, p);
        p.setColor(team);
        c.drawCircle(x, y - 12f * s, 9f * s, p);
        p.setColor(teamDark);
        tempRect.set(x - 10f * s, y - 12f * s, x + 10f * s, y - 8f * s);
        c.drawRoundRect(tempRect, 3f * s, 3f * s, p);

        p.setColor(Color.rgb(43, 41, 42));
        if (bossFace || !blue) {
            stroke.setColor(Color.rgb(43, 41, 42));
            stroke.setStrokeWidth(1.7f * s);
            c.drawLine(x - 4.5f * s, y - 7f * s, x - 1f * s, y - 8.5f * s, stroke);
            c.drawLine(x + 4.5f * s, y - 7f * s, x + 1f * s, y - 8.5f * s, stroke);
        }
        c.drawCircle(x - 3f * s, y - 5.5f * s, 1.2f * s, p);
        c.drawCircle(x + 3f * s, y - 5.5f * s, 1.2f * s, p);

        p.setColor(Color.rgb(42, 47, 54));
        tempRect.set(x - 1f * s, y - 1f * s, x + 11f * s, y + 3.5f * s);
        c.drawRoundRect(tempRect, 2f * s, 2f * s, p);
        p.setColor(teamDark);
        tempRect.set(x + 6f * s, y - 0.5f * s, x + 11f * s, y + 2.5f * s);
        c.drawRoundRect(tempRect, 1f * s, 1f * s, p);

        if (blue && flashTimer > 0f && rng.nextFloat() < 0.45f) {
            p.setColor(Color.rgb(255, 214, 79));
            tempPath.reset();
            tempPath.moveTo(x + 11f * s, y + 1f * s);
            tempPath.lineTo(x + 18f * s, y - 2f * s);
            tempPath.lineTo(x + 15f * s, y + 2f * s);
            tempPath.lineTo(x + 19f * s, y + 5f * s);
            tempPath.lineTo(x + 11f * s, y + 3f * s);
            tempPath.close();
            c.drawPath(tempPath, p);
        }
    }

    private void drawCommander(Canvas c, float x, float y) {
        float s = den * 1.15f;
        p.setColor(Color.argb(70, 0, 0, 0));
        tempRect.set(x - 20f * s, y + 18f * s, x + 20f * s, y + 28f * s);
        c.drawOval(tempRect, p);

        p.setColor(Color.rgb(197, 134, 49));
        c.drawCircle(x, y + 3f * s, 18f * s, p);
        p.setColor(Color.rgb(75, 105, 66));
        c.drawCircle(x, y - 5f * s, 13f * s, p);
        p.setColor(Color.rgb(39, 58, 36));
        tempRect.set(x - 14f * s, y - 10f * s, x + 14f * s, y - 2f * s);
        c.drawRoundRect(tempRect, 4f * s, 4f * s, p);
        p.setColor(Color.rgb(44, 48, 54));
        tempRect.set(x - 6f * s, y - 18f * s, x + 6f * s, y - 2f * s);
        c.drawRoundRect(tempRect, 3f * s, 3f * s, p);
        p.setColor(Color.rgb(33, 149, 243));
        tempRect.set(x - 3f * s, y - 23f * s, x + 3f * s, y - 14f * s);
        c.drawRoundRect(tempRect, 2f * s, 2f * s, p);
        if (flashTimer > 0f) {
            p.setColor(Color.rgb(255, 172, 50));
            c.drawCircle(x, y - 26f * s, 5f * s, p);
            p.setColor(Color.rgb(255, 238, 121));
            c.drawCircle(x, y - 26f * s, 2.5f * s, p);
        }
    }

    private void drawCountBubble(Canvas c, float x, float y, int count, boolean blue, float scale) {
        float s = den * scale;
        float w = Math.max(56f * s, (26f + String.valueOf(count).length() * 16f) * s);
        float h = 34f * s;
        p.setColor(blue ? Color.rgb(31, 153, 245) : Color.rgb(235, 63, 59));
        p.setShadowLayer(4f * s, 0, 2f * s, Color.argb(80, 0, 0, 0));
        tempRect.set(x - w * 0.5f, y - h * 0.5f, x + w * 0.5f, y + h * 0.5f);
        c.drawRoundRect(tempRect, 5f * s, 5f * s, p);
        p.clearShadowLayer();
        tempPath.reset();
        tempPath.moveTo(x - 7f * s, y + h * 0.5f);
        tempPath.lineTo(x + 7f * s, y + h * 0.5f);
        tempPath.lineTo(x, y + h * 0.5f + 9f * s);
        tempPath.close();
        c.drawPath(tempPath, p);
        p.setColor(Color.WHITE);
        p.setTypeface(bold);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(22f * s);
        c.drawText(String.valueOf(count), x, y + 8f * s, p);
    }

    private void drawBullet(Canvas c, Bullet b) {
        stroke.setColor(Color.argb(95, 72, 195, 255));
        stroke.setStrokeWidth(6f * den);
        c.drawLine(b.x, b.y + 14f * den, b.x - b.vx * 0.008f, b.y, stroke);
        stroke.setColor(Color.rgb(191, 241, 255));
        stroke.setStrokeWidth(2.2f * den);
        c.drawLine(b.x, b.y + 12f * den, b.x, b.y, stroke);
    }

    private void drawEnemyBullet(Canvas c, EnemyBullet b) {
        stroke.setColor(Color.argb(120, 255, 128, 50));
        stroke.setStrokeWidth(5f * den);
        c.drawLine(b.x, b.y - 12f * den, b.x, b.y, stroke);
        stroke.setColor(Color.rgb(255, 232, 108));
        stroke.setStrokeWidth(2f * den);
        c.drawLine(b.x, b.y - 9f * den, b.x, b.y, stroke);
    }

    private void drawParticle(Canvas c, Particle q) {
        float alpha = clamp(q.life / 0.55f, 0f, 1f);
        p.setColor(Color.argb((int)(255 * alpha), Color.red(q.color), Color.green(q.color), Color.blue(q.color)));
        c.drawCircle(q.x, q.y, q.radius * Math.max(0.4f, alpha), p);
    }

    private void drawHud(Canvas c) {
        p.setTypeface(bold);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(14f * den);
        p.setColor(Color.argb(200, 22, 35, 49));
        tempRect.set(12f * den, 12f * den, 132f * den, 50f * den);
        c.drawRoundRect(tempRect, 10f * den, 10f * den, p);
        p.setColor(Color.WHITE);
        c.drawText("SCORE " + score, 22f * den, 29f * den, p);
        p.setTextSize(10f * den);
        c.drawText("BEST " + highScore + "   GUN " + gunTier, 22f * den, 43f * den, p);

        float x = W - 12f * den;
        p.setTextAlign(Paint.Align.RIGHT);
        p.setTextSize(10f * den);
        if (rapidTimer > 0f) {
            p.setColor(Color.rgb(78, 211, 255));
            c.drawText(String.format(Locale.US, "RAPID %.0fs", rapidTimer), x, 24f * den, p);
        }
        if (doubleDamageTimer > 0f) {
            p.setColor(Color.rgb(255, 215, 77));
            c.drawText(String.format(Locale.US, "2x DMG %.0fs", doubleDamageTimer), x, 39f * den, p);
        }
        if (shieldTimer > 0f) {
            p.setColor(Color.rgb(120, 220, 255));
            c.drawText(String.format(Locale.US, "SHIELD %.0fs", shieldTimer), x, 54f * den, p);
        }

        if (bannerTimer > 0f && mode == MODE_PLAYING) {
            float a = Math.min(1f, bannerTimer * 2f);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(bold);
            p.setTextSize(20f * den);
            p.setColor(Color.argb((int)(255 * a), 255, 255, 255));
            p.setShadowLayer(4f * den, 0, 2f * den, Color.argb((int)(150 * a), 0, 0, 0));
            c.drawText(bannerText, W * 0.5f, H * 0.15f, p);
            p.clearShadowLayer();
        }

        if (mode == MODE_PLAYING && focusedCrate != null && !focusedCrate.dead) {
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(10f * den);
            p.setColor(Color.rgb(255, 235, 90));
            c.drawText("FOCUSING POWERUP — TAP EMPTY ROAD TO CANCEL", W * 0.5f, H - 18f * den, p);
        }
    }

    private void drawReady(Canvas c) {
        p.setColor(Color.argb(175, 7, 20, 36));
        c.drawRect(0, 0, W, H, p);
        p.setTypeface(bold);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(Color.WHITE);
        p.setTextSize(42f * den);
        c.drawText("LANE", W * 0.5f, H * 0.30f, p);
        p.setColor(Color.rgb(54, 165, 255));
        c.drawText("COMMAND", W * 0.5f, H * 0.35f, p);
        p.setColor(Color.WHITE);
        p.setTextSize(14f * den);
        c.drawText("DRAG LEFT / RIGHT • AUTO FIRE • GROW THE SQUAD", W * 0.5f, H * 0.41f, p);
        c.drawText("TAP A NUMBERED CRATE TO FOCUS FIRE", W * 0.5f, H * 0.44f, p);

        p.setColor(Color.rgb(43, 166, 255));
        tempRect.set(W * 0.24f, H * 0.53f, W * 0.76f, H * 0.61f);
        p.setShadowLayer(10f * den, 0, 4f * den, Color.argb(100, 0, 0, 0));
        c.drawRoundRect(tempRect, 14f * den, 14f * den, p);
        p.clearShadowLayer();
        p.setColor(Color.WHITE);
        p.setTextSize(28f * den);
        c.drawText("PLAY", W * 0.5f, H * 0.585f, p);

        p.setTextSize(11f * den);
        p.setColor(Color.rgb(214, 229, 242));
        c.drawText("NO ADS • NO PURCHASES • OFFLINE", W * 0.5f, H * 0.68f, p);
    }

    private void drawGameOver(Canvas c) {
        p.setColor(Color.argb(188, 9, 16, 27));
        c.drawRect(0, 0, W, H, p);
        p.setTypeface(bold);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(Color.WHITE);
        p.setTextSize(34f * den);
        c.drawText("RUN OVER", W * 0.5f, H * 0.39f, p);
        p.setTextSize(18f * den);
        p.setColor(Color.rgb(87, 190, 255));
        c.drawText("SCORE " + score, W * 0.5f, H * 0.45f, p);
        p.setColor(Color.WHITE);
        p.setTextSize(13f * den);
        c.drawText("BEST " + highScore + "   •   BOSSES " + bossKills, W * 0.5f, H * 0.49f, p);

        p.setColor(Color.rgb(45, 164, 255));
        tempRect.set(W * 0.25f, H * 0.56f, W * 0.75f, H * 0.64f);
        c.drawRoundRect(tempRect, 14f * den, 14f * den, p);
        p.setColor(Color.WHITE);
        p.setTextSize(23f * den);
        c.drawText("PLAY AGAIN", W * 0.5f, H * 0.615f, p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();

        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            if (mode == MODE_READY) {
                mode = MODE_PLAYING;
                lastFrameNanos = 0L;
                return true;
            }
            if (mode == MODE_GAME_OVER) {
                resetRun(true);
                lastFrameNanos = 0L;
                return true;
            }

            Crate tapped = findCrateAt(x, y);
            if (tapped != null) {
                focusedCrate = tapped;
                return true;
            } else {
                focusedCrate = null;
            }
            targetX = clamp(x, roadLeft(playerY) + 24f * den, roadRight(playerY) - 24f * den);
            return true;
        }
        if (e.getAction() == MotionEvent.ACTION_MOVE && mode == MODE_PLAYING) {
            targetX = clamp(x, roadLeft(playerY) + 24f * den, roadRight(playerY) - 24f * den);
            return true;
        }
        return true;
    }

    private Crate findCrateAt(float x, float y) {
        for (int i = crates.size() - 1; i >= 0; i--) {
            Crate q = crates.get(i);
            float r = q.radius() * den * 1.35f;
            if (hit(x, y, q.x, q.y, r)) return q;
        }
        return null;
    }

    private float roadLeft(float y) {
        float t = clamp(y / Math.max(1f, H), 0f, 1f);
        return W * (0.18f - 0.10f * t);
    }

    private float roadRight(float y) {
        return W - roadLeft(y);
    }

    private float roadWidth(float y) {
        return roadRight(y) - roadLeft(y);
    }

    private float perspective(float y) {
        float t = clamp(y / Math.max(1f, H), 0f, 1f);
        return 0.58f + 0.48f * t;
    }

    private float squadRadius() {
        return Math.min(105f * den, 30f * den + (float)Math.sqrt(Math.max(1, squad)) * 5.8f * den);
    }

    private boolean hit(float x1, float y1, float x2, float y2, float r) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return dx * dx + dy * dy <= r * r;
    }

    private static float clamp(float v, float a, float b) {
        return Math.max(a, Math.min(b, v));
    }

    private static class Bullet {
        float x, y, vx, vy, damage, life = 1.8f;
        Bullet(float x, float y, float vx, float vy, float damage) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.damage = damage;
        }
    }

    private static class EnemyBullet {
        float x, y, vx, vy, life = 4f;
        int damage;
        EnemyBullet(float x, float y, float vx, float vy, int damage) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.damage = damage;
        }
    }

    private static class EnemyGroup {
        float x, y, hp, maxHp, fireTimer = 0.6f;
        int initialCount;
        boolean boss, dead = false;
        EnemyGroup(float x, float y, int count, boolean boss) {
            this.x = x; this.y = y; this.hp = count; this.maxHp = count;
            this.initialCount = count; this.boss = boss;
        }
        float hitRadius() { return boss ? 60f : 34f + (float)Math.sqrt(Math.max(1f, hp)) * 3.4f; }
    }

    private static class Gate {
        static final int TYPE_ADD = 0;
        static final int TYPE_MULTIPLY = 1;
        static final int TYPE_SUBTRACT = 2;
        float x1, x2, y;
        String label;
        int value, type;
        boolean positive, used = false;
        Gate(float x1, float x2, float y, String label, int value, boolean positive, int type) {
            this.x1 = x1; this.x2 = x2; this.y = y; this.label = label;
            this.value = value; this.positive = positive; this.type = type;
        }
    }

    private static class Reward {
        static final int ADD = 0;
        static final int MULTIPLY = 1;
        static final int GUN = 2;
        static final int RAPID = 3;
        static final int DOUBLE_DAMAGE = 4;
        static final int SHIELD = 5;
        String label;
        int type, value;
        Reward(String label, int type, int value) {
            this.label = label; this.type = type; this.value = value;
        }
    }

    private static class Crate {
        float x, y, hp, maxHp;
        Reward reward;
        boolean dead = false;
        Crate(float x, float y, int hp, Reward reward) {
            this.x = x; this.y = y; this.hp = hp; this.maxHp = hp; this.reward = reward;
        }
        float radius() { return 34f; }
    }

    private static class Obstacle {
        float x, y, w, h;
        boolean hit = false;
        Obstacle(float x, float y, float w, float h) {
            this.x = x; this.y = y; this.w = w; this.h = h;
        }
    }

    private static class Particle {
        float x, y, vx, vy, life, radius;
        int color;
        Particle(float x, float y, float vx, float vy, float life, int color, float radius) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy;
            this.life = life; this.color = color; this.radius = radius;
        }
    }
}
