using Microsoft.EntityFrameworkCore;
using Pomelo.EntityFrameworkCore.MySql;
using webapi.Data;

var builder = WebApplication.CreateBuilder(new WebApplicationOptions
{
    Args = args,
    // 静态文件根目录设为 html（而非默认的 wwwroot）
    WebRootPath = "html"
});

// 固定监听地址（生产环境无 launchSettings.json 时默认 5000，这里统一为 8800）
builder.WebHost.UseUrls("http://0.0.0.0:8800");

// 1. 配置 CORS 策略 (放在 AddDbContext 之前或之后都可以)
builder.Services.AddCors(options =>
{
    options.AddPolicy("AllowAll",
        policy =>
        {
            policy.AllowAnyOrigin()
                  .AllowAnyMethod()
                  .AllowAnyHeader();
        });
});

// 2. 配置数据库上下文
builder.Services.AddDbContext<AppDbContext>(options =>
    options.UseMySql(builder.Configuration.GetConnectionString("DefaultConnection"),
    ServerVersion.AutoDetect(builder.Configuration.GetConnectionString("DefaultConnection"))));

// 3. 注册后台服务
builder.Services.AddHostedService<webapi.Services.PhotoCleanupService>();

// 4. 添加控制器 + Swagger 支持
builder.Services.AddControllers();
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen();

var app = builder.Build();

// 4. 启动时自动应用数据库迁移（其他电脑无需手动 dotnet ef database update）
using (var scope = app.Services.CreateScope())
{
    var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
    db.Database.Migrate();

    // 5a. 自动应用照片功能的数据库变更（幂等 SQL）
    using (var conn = db.Database.GetDbConnection())
    {
        await conn.OpenAsync();
        using var cmd = conn.CreateCommand();

        // 添加 require_photo 列（如果不存在）
        cmd.CommandText = @"
            SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'inspection_templates'
              AND COLUMN_NAME = 'require_photo'";
        var colExists = Convert.ToInt64(await cmd.ExecuteScalarAsync()) > 0;
        if (!colExists)
        {
            cmd.CommandText = "ALTER TABLE inspection_templates ADD COLUMN require_photo TINYINT(1) NOT NULL DEFAULT 0";
            await cmd.ExecuteNonQueryAsync();
        }

        // 扩展 inspection_records.status 列长度（从 VARCHAR(10) → VARCHAR(20) 以容纳 "pending"）
        cmd.CommandText = @"
            SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'inspection_records'
              AND COLUMN_NAME = 'status'";
        var currentLen = await cmd.ExecuteScalarAsync();
        if (currentLen != DBNull.Value && Convert.ToInt32(currentLen) < 20)
        {
            cmd.CommandText = "ALTER TABLE inspection_records MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'submitted'";
            await cmd.ExecuteNonQueryAsync();
        }

        // 创建 inspection_photos 表（如果不存在）
        cmd.CommandText = @"
            SELECT COUNT(*) FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'inspection_photos'";
        var tableExists = Convert.ToInt64(await cmd.ExecuteScalarAsync()) > 0;
        if (!tableExists)
        {
            cmd.CommandText = @"
                CREATE TABLE inspection_photos (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    record_id INT NOT NULL,
                    item_name VARCHAR(100) NOT NULL,
                    photo_path VARCHAR(500) NOT NULL,
                    thumbnail_path VARCHAR(500) DEFAULT NULL,
                    photo_order INT NOT NULL DEFAULT 0,
                    uploaded_by VARCHAR(50) NOT NULL DEFAULT '',
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_photos_record (record_id),
                    INDEX idx_photos_item (record_id, item_name),
                    CONSTRAINT fk_photos_record FOREIGN KEY (record_id)
                        REFERENCES inspection_records(id) ON DELETE CASCADE
                )";
            await cmd.ExecuteNonQueryAsync();
        }
    }
}

// 5. 使用 CORS 中间件
app.UseCors("AllowAll");

// 6. 静态文件服务 — 从 html/ 目录提供前端页面
app.UseDefaultFiles();
app.UseStaticFiles();

// 7. 配置管道
if (app.Environment.IsDevelopment())
{
    app.UseSwagger();
    app.UseSwaggerUI();
}

app.UseHttpsRedirection();
app.UseAuthorization();
app.MapControllers();

app.Run();
