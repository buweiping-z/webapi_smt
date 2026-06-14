using Microsoft.EntityFrameworkCore;
using Pomelo.EntityFrameworkCore.MySql;
using webapi.Data;

var builder = WebApplication.CreateBuilder(args);

// 1. 添加 CORS 策略 (放在 AddDbContext 之前或之后都可以)
builder.Services.AddCors(options =>
{
    options.AddPolicy("AllowAll",
        policy =>
        {
            // 允许所有来源、所有请求头和所有请求方法
            policy.AllowAnyOrigin()
                  .AllowAnyMethod()
                  .AllowAnyHeader();
        });
});

// 2. 配置数据库上下文
builder.Services.AddDbContext<AppDbContext>(options =>
    options.UseMySql(builder.Configuration.GetConnectionString("DefaultConnection"),
    ServerVersion.AutoDetect(builder.Configuration.GetConnectionString("DefaultConnection"))));

// 3. 添加控制器和 Swagger 服务
builder.Services.AddControllers();
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen();

var app = builder.Build();

// 4. 启用 CORS 中间件 (顺序很重要：要在 UseHttpsRedirection 和 UseAuthorization 之前)
app.UseCors("AllowAll");

// 5. 配置开发环境
if (app.Environment.IsDevelopment())
{
    app.UseSwagger();
    app.UseSwaggerUI();
}

app.UseHttpsRedirection();
app.UseAuthorization();
app.MapControllers();

app.Run();